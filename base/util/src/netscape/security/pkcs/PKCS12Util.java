// --- BEGIN COPYRIGHT BLOCK ---
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; version 2 of the License.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
//
// (C) 2016 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package netscape.security.pkcs;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.logging.Logger;

import org.mozilla.jss.CryptoManager;
import org.mozilla.jss.asn1.ASN1Util;
import org.mozilla.jss.asn1.ASN1Value;
import org.mozilla.jss.asn1.BMPString;
import org.mozilla.jss.asn1.OCTET_STRING;
import org.mozilla.jss.asn1.SEQUENCE;
import org.mozilla.jss.asn1.SET;
import org.mozilla.jss.crypto.Cipher;
import org.mozilla.jss.crypto.CryptoStore;
import org.mozilla.jss.crypto.CryptoToken;
import org.mozilla.jss.crypto.EncryptionAlgorithm;
import org.mozilla.jss.crypto.IVParameterSpec;
import org.mozilla.jss.crypto.KeyGenAlgorithm;
import org.mozilla.jss.crypto.KeyGenerator;
import org.mozilla.jss.crypto.KeyWrapAlgorithm;
import org.mozilla.jss.crypto.KeyWrapper;
import org.mozilla.jss.crypto.ObjectNotFoundException;
import org.mozilla.jss.crypto.PBEAlgorithm;
import org.mozilla.jss.crypto.PrivateKey;
import org.mozilla.jss.crypto.SymmetricKey;
import org.mozilla.jss.crypto.X509Certificate;
import org.mozilla.jss.pkcs12.AuthenticatedSafes;
import org.mozilla.jss.pkcs12.CertBag;
import org.mozilla.jss.pkcs12.PFX;
import org.mozilla.jss.pkcs12.PasswordConverter;
import org.mozilla.jss.pkcs12.SafeBag;
import org.mozilla.jss.pkix.primitive.EncryptedPrivateKeyInfo;
import org.mozilla.jss.pkix.primitive.PrivateKeyInfo;
import org.mozilla.jss.util.Password;

public class PKCS12Util {

    private static Logger logger = Logger.getLogger(PKCS12Util.class.getName());

    PFX pfx;

    byte[] getEncodedKey(PrivateKey privateKey) throws Exception {

        CryptoManager cm = CryptoManager.getInstance();
        CryptoToken token = cm.getInternalKeyStorageToken();

        KeyGenerator kg = token.getKeyGenerator(KeyGenAlgorithm.DES3);
        SymmetricKey sk = kg.generate();

        KeyWrapper wrapper = token.getKeyWrapper(KeyWrapAlgorithm.DES3_CBC_PAD);
        byte[] iv = { 0x1, 0x1, 0x1, 0x1, 0x1, 0x1, 0x1, 0x1 };
        IVParameterSpec param = new IVParameterSpec(iv);
        wrapper.initWrap(sk, param);
        byte[] enckey = wrapper.wrap(privateKey);

        Cipher c = token.getCipherContext(EncryptionAlgorithm.DES3_CBC_PAD);
        c.initDecrypt(sk, param);
        return c.doFinal(enckey);
    }

    public void addKeyBag(PrivateKey privateKey, X509Certificate x509cert,
            Password pass, byte[] localKeyID, SEQUENCE safeContents) throws Exception {

        PasswordConverter passConverter = new PasswordConverter();
        byte salt[] = { 0x01, 0x01, 0x01, 0x01 };
        byte[] priData = getEncodedKey(privateKey);

        PrivateKeyInfo pki = (PrivateKeyInfo)
                ASN1Util.decode(PrivateKeyInfo.getTemplate(), priData);

        ASN1Value key = EncryptedPrivateKeyInfo.createPBE(
                PBEAlgorithm.PBE_SHA1_DES3_CBC,
                pass, salt, 1, passConverter, pki);

        SET keyAttrs = createBagAttrs(
                x509cert.getSubjectDN().toString(), localKeyID);

        SafeBag keyBag = new SafeBag(SafeBag.PKCS8_SHROUDED_KEY_BAG,
                key, keyAttrs);

        safeContents.addElement(keyBag);
    }

    public byte[] addCertBag(X509Certificate x509cert, String nickname,
            SEQUENCE safeContents) throws Exception {

        ASN1Value cert = new OCTET_STRING(x509cert.getEncoded());
        byte[] localKeyID = createLocalKeyID(x509cert);

        SET certAttrs = null;
        if (nickname != null)
            certAttrs = createBagAttrs(nickname, localKeyID);

        SafeBag certBag = new SafeBag(SafeBag.CERT_BAG,
                new CertBag(CertBag.X509_CERT_TYPE, cert), certAttrs);

        safeContents.addElement(certBag);

        return localKeyID;
    }

    byte[] createLocalKeyID(X509Certificate cert) throws Exception {

        // SHA1 hash of the X509Cert DER encoding
        byte[] certDer = cert.getEncoded();

        MessageDigest md = MessageDigest.getInstance("SHA");

        md.update(certDer);
        return md.digest();
    }

    SET createBagAttrs(String nickname, byte localKeyID[])
            throws Exception {

        SET attrs = new SET();
        SEQUENCE nicknameAttr = new SEQUENCE();

        nicknameAttr.addElement(SafeBag.FRIENDLY_NAME);
        SET nicknameSet = new SET();

        nicknameSet.addElement(new BMPString(nickname));
        nicknameAttr.addElement(nicknameSet);
        attrs.addElement(nicknameAttr);
        SEQUENCE localKeyAttr = new SEQUENCE();

        localKeyAttr.addElement(SafeBag.LOCAL_KEY_ID);
        SET localKeySet = new SET();

        localKeySet.addElement(new OCTET_STRING(localKeyID));
        localKeyAttr.addElement(localKeySet);
        attrs.addElement(localKeyAttr);

        return attrs;
    }

    public void loadFromNSS(Password password) throws Exception {

        logger.info("Loading data from NSS database");

        CryptoManager cm = CryptoManager.getInstance();
        CryptoToken token = cm.getInternalKeyStorageToken();
        CryptoStore store = token.getCryptoStore();

        SEQUENCE encSafeContents = new SEQUENCE();
        SEQUENCE safeContents = new SEQUENCE();

        logger.fine("Loading certificates:");

        X509Certificate[] certs = store.getCertificates();

        for (X509Certificate cert : certs) {
            String nickname = cert.getNickname();

            try {
                PrivateKey prikey = cm.findPrivKeyByCert(cert);
                logger.fine(" - cert " + nickname + " with private key");

                byte localKeyID[] = addCertBag(cert, nickname, safeContents);
                addKeyBag(prikey, cert, password, localKeyID, encSafeContents);

            } catch (ObjectNotFoundException e) {
                logger.fine(" - cert " + nickname + " without private key");
                addCertBag(cert, nickname, safeContents);
            }
        }

        AuthenticatedSafes authSafes = new AuthenticatedSafes();
        authSafes.addSafeContents(safeContents);
        authSafes.addSafeContents(encSafeContents);

        pfx = new PFX(authSafes);
    }

    public void storeIntoPKCS12(String filename, Password password) throws Exception {

        logger.info("Storing data into PKCS #12 file");

        pfx.computeMacData(password, null, 5);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        pfx.encode(bos);
        byte[] data = bos.toByteArray();

        try (FileOutputStream fos = new FileOutputStream(filename)) {
            fos.write(data);
        }
    }

    public void exportData(String filename, Password password) throws Exception {

        loadFromNSS(password);
        storeIntoPKCS12(filename, password);
    }
}
