package com.cowork.applet.domain.environment.repository.impl

// Field names are escaped because `value` is also a SurrealQL keyword.
// Records have generated IDs, so an upsert updates by the (appletId, name) unique index and creates when nothing matched.
private[impl] object SurrealEnvironmentStatements:
  def findAll(table: String): String =
    s"SELECT * OMIT id FROM $table WHERE appletId = $$appletId ORDER BY name;"

  def upsert(table: String, valueField: String): String =
    s"""LET $$updated = (UPDATE $table SET `$valueField` = $$entry.`$valueField`, updatedAt = $$entry.updatedAt
       |  WHERE appletId = $$entry.appletId AND name = $$entry.name);
       |IF array::len($$updated) == 0 { CREATE $table CONTENT $$entry RETURN NONE; };""".stripMargin

  def delete(table: String): String =
    s"DELETE $table WHERE appletId = $$appletId AND name = $$name RETURN BEFORE;"
