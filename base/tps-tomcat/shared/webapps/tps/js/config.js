/* --- BEGIN COPYRIGHT BLOCK ---
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Copyright (C) 2013 Red Hat, Inc.
 * All rights reserved.
 * --- END COPYRIGHT BLOCK ---
 *
 * @author Endi S. Dewata
 */

var ConfigModel = Model.extend({
    url: function() {
        return "/tps/rest/config";
    },
    parseResponse: function(response) {
        return {
            id: "config",
            status: response.Status,
            properties: response.Properties.Property
        };
    },
    createRequest: function(entry) {
        return {
            Status: entry.status,
            Properties: {
                Property: entry.properties
            }
        };
    }
});

var PropertiesTableItem = TableItem.extend({
    initialize: function(options) {
        var self = this;
        PropertiesTableItem.__super__.initialize.call(self, options);
    },
    renderIDColumn: function(td) {
        var self = this;

        // in view mode all properties are read-only
        if (self.table.mode == "view") {
            self.renderColumn(td);
            return;
        }

        // in edit mode all properties are editable
        PropertiesTableItem.__super__.renderIDColumn.call(self, td);
    }
});

var ConfigPage = EntryWithPropertiesPage.extend({
    initialize: function(options) {
        var self = this;
        options.model = new ConfigModel();
        options.tableItem = PropertiesTableItem;
        options.tableSize = 15;
        ConfigPage.__super__.initialize.call(self, options);
    }
});