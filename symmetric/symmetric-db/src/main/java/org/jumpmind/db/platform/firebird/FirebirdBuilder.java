package org.jumpmind.db.platform.firebird;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.Writer;
import java.sql.Types;
import java.util.Iterator;
import java.util.List;

import org.jumpmind.db.IDatabasePlatform;
import org.jumpmind.db.alter.AddColumnChange;
import org.jumpmind.db.alter.AddPrimaryKeyChange;
import org.jumpmind.db.alter.RemoveColumnChange;
import org.jumpmind.db.alter.TableChange;
import org.jumpmind.db.model.Column;
import org.jumpmind.db.model.Database;
import org.jumpmind.db.model.Index;
import org.jumpmind.db.model.Table;
import org.jumpmind.db.platform.SqlBuilder;
import org.jumpmind.db.util.Jdbc3Utils;
import org.jumpmind.util.Log;

/*
 * The SQL Builder for the FireBird database.
 */
public class FirebirdBuilder extends SqlBuilder {
    public FirebirdBuilder(Log log, IDatabasePlatform platform, Writer writer) {
        super(log, platform, writer);
        addEscapedCharSequence("'", "''");
    }

    @Override
    public void createTable(Database database, Table table)  {
        super.createTable(database, table);

        // creating generator and trigger for auto-increment
        Column[] columns = table.getAutoIncrementColumns();

        for (int idx = 0; idx < columns.length; idx++) {
            writeAutoIncrementCreateStmts(database, table, columns[idx]);
        }
    }

    @Override
    public void dropTable(Table table)  {
        // dropping generators for auto-increment
        Column[] columns = table.getAutoIncrementColumns();

        for (int idx = 0; idx < columns.length; idx++) {
            writeAutoIncrementDropStmts(table, columns[idx]);
        }
        super.dropTable(table);
    }

    /*
     * Writes the creation statements to make the given column an auto-increment
     * column.
     * 
     * @param database The database model
     * 
     * @param table The table
     * 
     * @param column The column to make auto-increment
     */
    private void writeAutoIncrementCreateStmts(Database database, Table table, Column column)
             {
        print("CREATE GENERATOR ");
        printIdentifier(getGeneratorName(table, column));
        printEndOfStatement();

        print("CREATE TRIGGER ");
        printIdentifier(getTriggerName(table, column));
        print(" FOR ");
        printlnIdentifier(getTableName(table));
        println("ACTIVE BEFORE INSERT POSITION 0 AS");
        print("BEGIN IF (NEW.");
        printIdentifier(getColumnName(column));
        print(" IS NULL) THEN NEW.");
        printIdentifier(getColumnName(column));
        print(" = GEN_ID(");
        printIdentifier(getGeneratorName(table, column));
        print(", 1); END");
        printEndOfStatement();
    }

    /*
     * Writes the statements to drop the auto-increment status for the given
     * column.
     * 
     * @param table The table
     * 
     * @param column The column to remove the auto-increment status for
     */
    private void writeAutoIncrementDropStmts(Table table, Column column)  {
        print("DROP TRIGGER ");
        printIdentifier(getTriggerName(table, column));
        printEndOfStatement();

        print("DROP GENERATOR ");
        printIdentifier(getGeneratorName(table, column));
        printEndOfStatement();
    }

    /*
     * Determines the name of the trigger for an auto-increment column.
     * 
     * @param table The table
     * 
     * @param column The auto-increment column
     * 
     * @return The trigger name
     */
    protected String getTriggerName(Table table, Column column) {
        String secondPart = column.getName();
        // make sure a backup table gets a different name than the original
        if (table.getName().endsWith("_")) {
            secondPart += "_";
        }
        return getConstraintName("trg", table, secondPart, null);
    }

    /*
     * Determines the name of the generator for an auto-increment column.
     * 
     * @param table The table
     * 
     * @param column The auto-increment column
     * 
     * @return The generator name
     */
    protected String getGeneratorName(Table table, Column column) {
        String secondPart = column.getName();
        // make sure a backup table gets a different name than the original
        if (table.getName().endsWith("_")) {
            secondPart += "_";
        }
        return getConstraintName("gen", table, secondPart, null);
    }

    @Override
    protected void writeColumnAutoIncrementStmt(Table table, Column column)  {
        // we're using a generator
    }

    @Override
    public String getSelectLastIdentityValues(Table table) {
        Column[] columns = table.getAutoIncrementColumns();

        if (columns.length == 0) {
            return null;
        } else {
            StringBuffer result = new StringBuffer();

            result.append("SELECT ");
            for (int idx = 0; idx < columns.length; idx++) {
                result.append("GEN_ID(");
                result.append(getDelimitedIdentifier(getGeneratorName(table, columns[idx])));
                result.append(", 0)");
            }
            result.append(" FROM RDB$DATABASE");
            return result.toString();
        }
    }

    @Override
    protected String getNativeDefaultValue(Column column) {
        if ((column.getTypeCode() == Types.BIT)
                || (Jdbc3Utils.supportsJava14JdbcTypes() && (column.getTypeCode() == Jdbc3Utils
                        .determineBooleanTypeCode()))) {
            return getDefaultValueHelper().convert(column.getDefaultValue(), column.getTypeCode(),
                    Types.SMALLINT).toString();
        } else {
            return super.getNativeDefaultValue(column);
        }
    }

    @Override
    public void createExternalForeignKeys(Database database)  {
        for (int idx = 0; idx < database.getTableCount(); idx++) {
            createExternalForeignKeys(database, database.getTable(idx));
        }
    }

    @Override
    public void writeExternalIndexDropStmt(Table table, Index index)  {
        // Index names in Firebird are unique to a schema and hence Firebird
        // does not
        // use the ON <tablename> clause
        print("DROP INDEX ");
        printIdentifier(getIndexName(index));
        printEndOfStatement();
    }

    @Override
    protected void processTableStructureChanges(Database currentModel, Database desiredModel,
            Table sourceTable, Table targetTable, List<TableChange> changes)  {
        // TODO: Dropping of primary keys is currently not supported because we
        // cannot
        // determine the pk constraint names and drop them in one go
        // (We could used a stored procedure if Firebird would allow them to use
        // DDL)
        // This will be easier once named primary keys are supported
        boolean pkColumnAdded = false;

        for (Iterator<TableChange> changeIt = changes.iterator(); changeIt.hasNext();) {
            TableChange change = changeIt.next();

            if (change instanceof AddColumnChange) {
                AddColumnChange addColumnChange = (AddColumnChange) change;

                // TODO: we cannot add columns to the primary key this way
                // because we would have to drop the pk first and then
                // add a new one afterwards which is not supported yet
                if (addColumnChange.getNewColumn().isPrimaryKey()) {
                    pkColumnAdded = true;
                } else {
                    processChange(currentModel, desiredModel, addColumnChange);
                    changeIt.remove();
                }
            } else if (change instanceof RemoveColumnChange) {
                RemoveColumnChange removeColumnChange = (RemoveColumnChange) change;

                // TODO: we cannot drop primary key columns this way
                // because we would have to drop the pk first and then
                // add a new one afterwards which is not supported yet
                if (!removeColumnChange.getColumn().isPrimaryKey()) {
                    processChange(currentModel, desiredModel, removeColumnChange);
                    changeIt.remove();
                }
            }
        }
        for (Iterator<TableChange> changeIt = changes.iterator(); changeIt.hasNext();) {
            TableChange change = changeIt.next();

            // we can only add a primary key if all columns are present in the
            // table
            // i.e. none was added during this alteration
            if ((change instanceof AddPrimaryKeyChange) && !pkColumnAdded) {
                processChange(currentModel, desiredModel, (AddPrimaryKeyChange) change);
                changeIt.remove();
            }
        }
    }

    /*
     * Processes the addition of a column to a table.
     * 
     * @param currentModel The current database schema
     * 
     * @param desiredModel The desired database schema
     * 
     * @param change The change object
     */
    protected void processChange(Database currentModel, Database desiredModel,
            AddColumnChange change)  {
        print("ALTER TABLE ");
        printlnIdentifier(getTableName(change.getChangedTable()));
        printIndent();
        print("ADD ");
        writeColumn(change.getChangedTable(), change.getNewColumn());
        printEndOfStatement();

        Table curTable = currentModel.findTable(change.getChangedTable().getName(),
                platform.isDelimitedIdentifierModeOn());

        if (!change.isAtEnd()) {
            Column prevColumn = change.getPreviousColumn();

            if (prevColumn != null) {
                // we need the corresponding column object from the current
                // table
                prevColumn = curTable.findColumn(prevColumn.getName(),
                        platform.isDelimitedIdentifierModeOn());
            }
            // Even though Firebird can only add columns, we can move them later
            // on
            print("ALTER TABLE ");
            printlnIdentifier(getTableName(change.getChangedTable()));
            printIndent();
            print("ALTER ");
            printIdentifier(getColumnName(change.getNewColumn()));
            print(" POSITION ");
            // column positions start at 1 in Firebird
            print(prevColumn == null ? "1" : String
                    .valueOf(curTable.getColumnIndex(prevColumn) + 2));
            printEndOfStatement();
        }
        if (change.getNewColumn().isAutoIncrement()) {
            writeAutoIncrementCreateStmts(currentModel, curTable, change.getNewColumn());
        }
        change.apply(currentModel, platform.isDelimitedIdentifierModeOn());
    }

    /*
     * Processes the removal of a column from a table.
     * 
     * @param currentModel The current database schema
     * 
     * @param desiredModel The desired database schema
     * 
     * @param change The change object
     */
    protected void processChange(Database currentModel, Database desiredModel,
            RemoveColumnChange change)  {
        if (change.getColumn().isAutoIncrement()) {
            writeAutoIncrementDropStmts(change.getChangedTable(), change.getColumn());
        }
        print("ALTER TABLE ");
        printlnIdentifier(getTableName(change.getChangedTable()));
        printIndent();
        print("DROP ");
        printIdentifier(getColumnName(change.getColumn()));
        printEndOfStatement();
        change.apply(currentModel, platform.isDelimitedIdentifierModeOn());
    }
}
