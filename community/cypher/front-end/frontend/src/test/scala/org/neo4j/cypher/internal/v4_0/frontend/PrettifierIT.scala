/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.v4_0.frontend

import org.neo4j.cypher.internal.v4_0.ast.Statement
import org.neo4j.cypher.internal.v4_0.ast.prettifier.{ExpressionStringifier, Prettifier}
import org.neo4j.cypher.internal.v4_0.parser.CypherParser
import org.neo4j.cypher.internal.v4_0.util.test_helpers.{CypherFunSuite, WindowsStringSafe}

class PrettifierIT extends CypherFunSuite {
  private implicit val windowsSafe: WindowsStringSafe.type = WindowsStringSafe

  val prettifier: Prettifier = Prettifier(ExpressionStringifier())

  val parser = new CypherParser
  val tests: Seq[(String, String)] =
    Seq[(String, String)](
      "return 42" -> "RETURN 42",
      "return 42 as x" -> "RETURN 42 AS x",
      "return 42 as `43`" -> "RETURN 42 AS `43`",
      "return distinct 42" -> "RETURN DISTINCT 42",

      "return distinct a, b as X, 3+3 as six order by b.prop, b.foo descending skip 1 limit 2" ->
        """RETURN DISTINCT a, b AS X, 3 + 3 AS six
          |  ORDER BY b.prop ASCENDING, b.foo DESCENDING
          |  SKIP 1
          |  LIMIT 2""".stripMargin,

      "match (a) return a" ->
        """MATCH (a)
          |RETURN a""".stripMargin,

      "match (a) where a.prop = 42 return a" ->
        """MATCH (a)
          |  WHERE a.prop = 42
          |RETURN a""".stripMargin,

      "match (a) with distinct a, b as X, 3+3 as six order by b.prop, b.foo descending skip 1 limit 2 where true" ->
        """MATCH (a)
          |WITH DISTINCT a, b AS X, 3 + 3 AS six
          |  ORDER BY b.prop ASCENDING, b.foo DESCENDING
          |  SKIP 1
          |  LIMIT 2
          |  WHERE true""".stripMargin,

      "create (a)--(b) RETURN a" ->
        """CREATE (a)--(b)
          |RETURN a""".stripMargin,

      "match (a:Label {prop: 1}) RETURN a" ->
        """MATCH (a:Label {prop: 1})
          |RETURN a""".stripMargin,

      "unwind [1,2,3] AS x RETURN x" ->
        """UNWIND [1, 2, 3] AS x
          |RETURN x""".stripMargin,

      "CALL nsp.proc()" ->
        """CALL nsp.proc()""".stripMargin,

      "CALL proc()" ->
        """CALL proc()""".stripMargin,

      "CALL nsp1.nsp2.proc()" ->
        """CALL nsp1.nsp2.proc()""".stripMargin,

      "CALL nsp.proc(a)" ->
        """CALL nsp.proc(a)""".stripMargin,

      "CALL nsp.proc(a,b)" ->
        """CALL nsp.proc(a, b)""".stripMargin,

      "CALL nsp.proc() yield x" ->
        """CALL nsp.proc() YIELD x""".stripMargin,

      "CALL nsp.proc() yield x, y" ->
        """CALL nsp.proc() YIELD x, y""".stripMargin,

      "match (n) SET n.prop = 1" ->
        """MATCH (n)
          |SET n.prop = 1""".stripMargin,

      "match (n) SET n.prop = 1, n.prop2 = 2" ->
        """MATCH (n)
          |SET n.prop = 1, n.prop2 = 2""".stripMargin,

      "match (n) SET n:Label" ->
        """MATCH (n)
          |SET n:Label""".stripMargin,

      "match (n) SET n:`La bel`" ->
        """MATCH (n)
          |SET n:`La bel`""".stripMargin,

      "match (n) SET n:Label:Bla" ->
        """MATCH (n)
          |SET n:Label:Bla""".stripMargin,

      "match (n) SET n += {prop: 1}" ->
        """MATCH (n)
          |SET n += {prop: 1}""".stripMargin,

      "match (n) SET n = {prop: 1}" ->
        """MATCH (n)
          |SET n = {prop: 1}""".stripMargin,

      "match (n) SET n:Label, n.prop = 1" ->
        """MATCH (n)
          |SET n:Label, n.prop = 1""".stripMargin,

      "match (n) DELETE n" ->
        """MATCH (n)
          |DELETE n""".stripMargin,

      "match (n), (m) DELETE n, m" ->
        """MATCH (n), (m)
          |DELETE n, m""".stripMargin,

      "merge (n)" ->
        "MERGE (n)",

      "merge (n)--(m)" ->
        "MERGE (n)--(m)",

      "merge (n:Label {prop:1})--(m)" ->
        "MERGE (n:Label {prop: 1})--(m)",

      "create INDEX ON :A(p)" ->
        "CREATE INDEX ON :A(p)",

      "create INDEX ON :A(p1, p2, p3)" ->
        "CREATE INDEX ON :A(p1, p2, p3)",

      "drop INDEX ON :A(p)" ->
        "DROP INDEX ON :A(p)",

      "drop INDEX ON :A(p1, p2, p3)" ->
        "DROP INDEX ON :A(p1, p2, p3)",

      "create CONSTRAINT ON (n:A) ASSERT (n.p) IS NODE KEY" ->
        "CREATE CONSTRAINT ON (n:A) ASSERT (n.p) IS NODE KEY",

      "create CONSTRAINT ON (n:A) ASSERT (n.p1, n.p2) IS NODE KEY" ->
        "CREATE CONSTRAINT ON (n:A) ASSERT (n.p1, n.p2) IS NODE KEY",

      "drop CONSTRAINT ON (n:A) ASSERT (n.p) IS NODE KEY" ->
        "DROP CONSTRAINT ON (n:A) ASSERT (n.p) IS NODE KEY",

      "drop CONSTRAINT ON (n:A) ASSERT (n.p1, n.p2) IS NODE KEY" ->
        "DROP CONSTRAINT ON (n:A) ASSERT (n.p1, n.p2) IS NODE KEY",

      "create CONSTRAINT ON (n:A) ASSERT (n.p) IS UNIQUE" ->
        "CREATE CONSTRAINT ON (n:A) ASSERT (n.p) IS UNIQUE",

      "create CONSTRAINT ON (n:A) ASSERT (n.p1, n.p2) IS UNIQUE" ->
        "CREATE CONSTRAINT ON (n:A) ASSERT (n.p1, n.p2) IS UNIQUE",

      "drop CONSTRAINT ON (n:A) ASSERT (n.p) IS UNIQUE" ->
        "DROP CONSTRAINT ON (n:A) ASSERT (n.p) IS UNIQUE",

      "drop CONSTRAINT ON (n:A) ASSERT (n.p1, n.p2) IS UNIQUE" ->
        "DROP CONSTRAINT ON (n:A) ASSERT (n.p1, n.p2) IS UNIQUE",

      "create CONSTRAINT ON (a:A) ASSERT exists(a.p)" ->
        "CREATE CONSTRAINT ON (a:A) ASSERT exists(a.p)",

      "drop CONSTRAINT ON (a:A) ASSERT exists(a.p)" ->
        "DROP CONSTRAINT ON (a:A) ASSERT exists(a.p)",

      "create CONSTRAINT ON ()-[r:R]-() ASSERT exists(r.p)" ->
        "CREATE CONSTRAINT ON ()-[r:R]-() ASSERT exists(r.p)",

      "drop CONSTRAINT ON ()-[r:R]-() ASSERT exists(r.p)" ->
        "DROP CONSTRAINT ON ()-[r:R]-() ASSERT exists(r.p)",

      "match (n) UNION match (n)" ->
        """MATCH (n)
          |UNION
          |MATCH (n)""".stripMargin,

      "match (n) UNION ALL match (n)" ->
        """MATCH (n)
          |UNION ALL
          |MATCH (n)""".stripMargin,

      "match (n) UNION match (n) UNION ALL RETURN $node AS n" ->
        """MATCH (n)
          |UNION
          |MATCH (n)
          |UNION ALL
          |RETURN $node AS n""".stripMargin,

      "create user abc set password 'foo'" ->
        "CATALOG CREATE USER abc SET PASSWORD '******' CHANGE REQUIRED SET STATUS ACTIVE",

      "create user `ab%$c` set password 'foo'" ->
        "CATALOG CREATE USER `ab%$c` SET PASSWORD '******' CHANGE REQUIRED SET STATUS ACTIVE",

      "alter user abc set password 'foo'" ->
        "CATALOG ALTER USER abc SET PASSWORD '******'",

      "alter user `ab%$c` set password 'foo'" ->
        "CATALOG ALTER USER `ab%$c` SET PASSWORD '******'",

      "alter user abc set status active" ->
        "CATALOG ALTER USER abc SET STATUS ACTIVE",

      "alter user abc set password 'foo' change required set status active" ->
        "CATALOG ALTER USER abc SET PASSWORD '******' CHANGE REQUIRED SET STATUS ACTIVE",

      "alter user abc set password change not required set status suspended" ->
        "CATALOG ALTER USER abc SET PASSWORD CHANGE NOT REQUIRED SET STATUS SUSPENDED",

      "drop user abc" ->
        "CATALOG DROP USER abc",

      "drop user `ab%$c`" ->
        "CATALOG DROP USER `ab%$c`",

      "create role abc" ->
        "CATALOG CREATE ROLE abc",

      "create role `ab%$c`" ->
        "CATALOG CREATE ROLE `ab%$c`",

      "drop role abc" ->
        "CATALOG DROP ROLE abc",

      "drop role `ab%$c`" ->
        "CATALOG DROP ROLE `ab%$c`",

      "grant role abc to xyz" ->
        "CATALOG GRANT ROLE abc TO xyz",

      "grant role `ab%$c` to `x%^yz`" ->
        "CATALOG GRANT ROLE `ab%$c` TO `x%^yz`",

      "revoke role abc from xyz" ->
        "CATALOG REVOKE ROLE abc FROM xyz",

      "revoke role `ab%$c` from `x%^yz`" ->
        "CATALOG REVOKE ROLE `ab%$c` FROM `x%^yz`",

      "show privileges" ->
        "CATALOG SHOW ALL PRIVILEGES",

      "show all privileges" ->
        "CATALOG SHOW ALL PRIVILEGES",

      "show user abc privileges" ->
        "CATALOG SHOW USER abc PRIVILEGES",

      "show  user `$aB%x`  privileges" ->
        "CATALOG SHOW USER `$aB%x` PRIVILEGES",

      "show user `$user` privileges" ->
        "CATALOG SHOW USER `$user` PRIVILEGES",

      "show role abc privileges" ->
        "CATALOG SHOW ROLE abc PRIVILEGES",

      "show  role `$aB%x`  privileges" ->
        "CATALOG SHOW ROLE `$aB%x` PRIVILEGES",

      "show role `$role` privileges" ->
        "CATALOG SHOW ROLE `$role` PRIVILEGES",

      "grant traverse on graph * to role" ->
        "CATALOG GRANT TRAVERSE ON GRAPH * NODES * (*) TO role",

      "grant traverse on graph * nodes * to role" ->
        "CATALOG GRANT TRAVERSE ON GRAPH * NODES * (*) TO role",

      "grant traverse on graph * nodes * (*) to role" ->
        "CATALOG GRANT TRAVERSE ON GRAPH * NODES * (*) TO role",

      "grant traverse on graph foo nodes * (*) to role" ->
        "CATALOG GRANT TRAVERSE ON GRAPH foo NODES * (*) TO role",

      "grant traverse on graph foo nodes A (*) to role" ->
        "CATALOG GRANT TRAVERSE ON GRAPH foo NODES A (*) TO role",

      "grant traverse on graph `#%¤` nodes `()/&` (*) to role" ->
        "CATALOG GRANT TRAVERSE ON GRAPH `#%¤` NODES `()/&` (*) TO role",

      "grant read (*) on graph * to role" ->
        "CATALOG GRANT READ (*) ON GRAPH * NODES * (*) TO role",

      "grant read (*) on graph * nodes * to role" ->
        "CATALOG GRANT READ (*) ON GRAPH * NODES * (*) TO role",

      "grant read (*) on graph * nodes * (*) to role" ->
        "CATALOG GRANT READ (*) ON GRAPH * NODES * (*) TO role",

      "grant read (*) on graph foo nodes * (*) to role" ->
        "CATALOG GRANT READ (*) ON GRAPH foo NODES * (*) TO role",

      "grant read (*) on graph foo nodes A (*) to role" ->
        "CATALOG GRANT READ (*) ON GRAPH foo NODES A (*) TO role",

      "grant read (bar) on graph foo nodes A (*) to role" ->
        "CATALOG GRANT READ (bar) ON GRAPH foo NODES A (*) TO role",

      "grant read ( `&bar` ) on graph `#%¤` nodes `()/&` (*) to role" ->
        "CATALOG GRANT READ (`&bar`) ON GRAPH `#%¤` NODES `()/&` (*) TO role",

      "revoke traverse on graph * from role" ->
        "CATALOG REVOKE TRAVERSE ON GRAPH * NODES * (*) FROM role",

      "revoke traverse on graph * nodes * from role" ->
        "CATALOG REVOKE TRAVERSE ON GRAPH * NODES * (*) FROM role",

      "revoke traverse on graph * nodes * (*) from role" ->
        "CATALOG REVOKE TRAVERSE ON GRAPH * NODES * (*) FROM role",

      "revoke traverse on graph foo nodes * (*) from role" ->
        "CATALOG REVOKE TRAVERSE ON GRAPH foo NODES * (*) FROM role",

      "revoke traverse on graph foo nodes A (*) from role" ->
        "CATALOG REVOKE TRAVERSE ON GRAPH foo NODES A (*) FROM role",

      "revoke traverse on graph `#%¤` nodes `()/&` (*) from role" ->
        "CATALOG REVOKE TRAVERSE ON GRAPH `#%¤` NODES `()/&` (*) FROM role",

      "revoke read (*) on graph * from role" ->
        "CATALOG REVOKE READ (*) ON GRAPH * NODES * (*) FROM role",

      "revoke read (*) on graph * nodes * from role" ->
        "CATALOG REVOKE READ (*) ON GRAPH * NODES * (*) FROM role",

      "revoke read (*) on graph * nodes * (*) from role" ->
        "CATALOG REVOKE READ (*) ON GRAPH * NODES * (*) FROM role",

      "revoke read (*) on graph foo nodes * (*) from role" ->
        "CATALOG REVOKE READ (*) ON GRAPH foo NODES * (*) FROM role",

      "revoke read (*) on graph foo nodes A (*) from role" ->
        "CATALOG REVOKE READ (*) ON GRAPH foo NODES A (*) FROM role",

      "revoke read (bar) on graph foo nodes A (*) from role" ->
        "CATALOG REVOKE READ (bar) ON GRAPH foo NODES A (*) FROM role",

      "revoke read ( `&bar` ) on graph `#%¤` nodes `()/&` (*) from role" ->
        "CATALOG REVOKE READ (`&bar`) ON GRAPH `#%¤` NODES `()/&` (*) FROM role",

      "catalog show database foO_Bar_42" ->
        "CATALOG SHOW DATABASE foO_Bar_42",

      "catalog create database foO_Bar_42" ->
        "CATALOG CREATE DATABASE foO_Bar_42",

      "catalog create database `foO_Bar_42`" ->
        "CATALOG CREATE DATABASE foO_Bar_42",

      "catalog create database `graph.db`" ->
        "CATALOG CREATE DATABASE `graph.db`",

      "catalog DROP database foO_Bar_42" ->
        "CATALOG DROP DATABASE foO_Bar_42",

      "catalog create graph com.neo4j.Users { MATCH (n) RETURN n }" ->
        """CATALOG CREATE GRAPH com.neo4j.Users {
          |MATCH (n)
          |RETURN n
          |}""".stripMargin,

      "catalog DROP graph com.neo4j.Users" ->
        "CATALOG DROP GRAPH com.neo4j.Users",

      "catalog create VIEW com.neo4j.Users($p, $k) { MATCH (n) WHERE n.p=$p RETURN n LIMIT $k }" ->
        """CATALOG CREATE VIEW com.neo4j.Users($p, $k) {
          |MATCH (n)
          |  WHERE n.p = $p
          |RETURN n
          |  LIMIT $k
          |}""".stripMargin,

      "catalog DROP VIEW com.neo4j.Users" ->
        "CATALOG DROP VIEW com.neo4j.Users",

      "load csv from '/import/data.csv' AS row create ({key: row[0]})" ->
        """LOAD CSV FROM "/import/data.csv" AS row
          |CREATE ({key: row[0]})""".stripMargin,

      "load csv WITH headers from '/import/data.csv' AS row create ({key: row[0]})" ->
        """LOAD CSV WITH HEADERS FROM "/import/data.csv" AS row
          |CREATE ({key: row[0]})""".stripMargin,

      "load csv from '/import/data.csv' AS row FIELDTERMINATOR '-' create ({key: row[0]})" ->
        """LOAD CSV FROM "/import/data.csv" AS row FIELDTERMINATOR "-"
          |CREATE ({key: row[0]})""".stripMargin,

      "USING periodic commit 30 load csv from '/import/data.csv' AS row create ({key: row[0]})" ->
        """USING PERIODIC COMMIT 30
          |LOAD CSV FROM "/import/data.csv" AS row
          |CREATE ({key: row[0]})""".stripMargin,

      "FOREACH ( n IN [1,2,3] | create ({key: n}) CREATE ({foreignKey: n}) )" ->
        """FOREACH ( n IN [1, 2, 3] |
          |  CREATE ({key: n})
          |  CREATE ({foreignKey: n})
          |)""".stripMargin,

      "create unique (a)--(b) RETURN a" ->
        """CREATE UNIQUE (a)--(b)
          |RETURN a""".stripMargin
    ) ++ startTests("node") ++ startTests("relationship")

  def startTests(entityType: String): Seq[(String, String)] = {
    val ENTITYTYPE = entityType.toUpperCase
    Seq(
      s"START x=$entityType(*) RETURN x" ->
        s"""START x = $ENTITYTYPE( * )
           |RETURN x""".stripMargin,

      s"START x=$entityType(42) RETURN x" ->
        s"""START x = $ENTITYTYPE( 42 )
           |RETURN x""".stripMargin,

      s"START x=$entityType(42,101) RETURN x" ->
        s"""START x = $ENTITYTYPE( 42, 101 )
           |RETURN x""".stripMargin,

      s"START x=$entityType($$param) RETURN x" ->
        s"""START x = $ENTITYTYPE( $$param )
           |RETURN x""".stripMargin,

      s"START x=$entityType($$param), y=$entityType(42,101) RETURN x, y" ->
        s"""START x = $ENTITYTYPE( $$param ),
           |      y = $ENTITYTYPE( 42, 101 )
           |RETURN x, y""".stripMargin
    )
  }

  tests foreach {
    case (inputString, expected) =>
      test(inputString) {
        val parsingResults: Statement = parser.parse(inputString)
        val str = prettifier.asString(parsingResults)
        str should equal(expected)
      }
  }

}
