/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive.client

import java.sql.Date

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat
import org.apache.hadoop.hive.serde2.`lazy`.LazySimpleSerDe
import org.apache.hadoop.mapred.TextInputFormat
import org.scalatest.BeforeAndAfterAll

import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{BooleanType, DateType, IntegerType, LongType, StringType, StructType}
import org.apache.spark.util.Utils

class HivePartitionFilteringSuite(version: String)
    extends HiveVersionSuite(version) with BeforeAndAfterAll {

  private val tryDirectSqlKey = HiveConf.ConfVars.METASTORE_TRY_DIRECT_SQL.varname

  private val testPartitionCount = 3 * 5 * 4

  private val storageFormat = CatalogStorageFormat(
    locationUri = None,
    inputFormat = Some(classOf[TextInputFormat].getName),
    outputFormat = Some(classOf[HiveIgnoreKeyTextOutputFormat[_, _]].getName),
    serde = Some(classOf[LazySimpleSerDe].getName()),
    compressed = false,
    properties = Map.empty
  )

  private def init(tryDirectSql: Boolean): HiveClient = {
    val hadoopConf = new Configuration()
    hadoopConf.setBoolean(tryDirectSqlKey, tryDirectSql)
    hadoopConf.set("hive.metastore.warehouse.dir", Utils.createTempDir().toURI().toString())
    val client = buildClient(hadoopConf)
    val tableSchema =
      new StructType().add("value", "int").add("ds", "int").add("h", "int").add("chunk", "string")
    val table = CatalogTable(
      identifier = TableIdentifier("test", Some("default")),
      tableType = CatalogTableType.MANAGED,
      schema = tableSchema,
      partitionColumnNames = Seq("ds", "h", "chunk"),
      storage = storageFormat)
    client.createTable(table, ignoreIfExists = false)

    val partitions =
      for {
        ds <- 20170101 to 20170103
        h <- 0 to 4
        chunk <- Seq("aa", "ab", "ba", "bb")
      } yield CatalogTablePartition(Map(
        "ds" -> ds.toString,
        "h" -> h.toString,
        "chunk" -> chunk
      ), storageFormat)
    assert(partitions.size == testPartitionCount)

    client.createPartitions(
      "default", "test", partitions, ignoreIfExists = false)
    client
  }

  private def attr(name: String): Attribute = {
    client.getTable("default", "test").partitionSchema.fields
        .find(field => field.name.equals(name)) match {
      case Some(field) => AttributeReference(field.name, field.dataType)()
      case None =>
        fail(s"Illegal name of partition attribute: $name")
    }
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    client = init(true)
  }

  test(s"getPartitionsByFilter returns all partitions when $tryDirectSqlKey=false") {
    val client = init(false)
    val filteredPartitions = client.getPartitionsByFilter(client.getTable("default", "test"),
      Seq(attr("ds") === 20170101), SQLConf.get.sessionLocalTimeZone)

    assert(filteredPartitions.size == testPartitionCount)
  }

  test("getPartitionsByFilter: ds<=>20170101") {
    // Should return all partitions where <=> is not supported
    testMetastorePartitionFiltering(
      attr("ds") <=> 20170101,
      20170101 to 20170103,
      0 to 4,
      "aa" :: "ab" :: "ba" :: "bb" :: Nil)
  }

  test("getPartitionsByFilter: ds=20170101") {
    testMetastorePartitionFiltering(
      attr("ds") === 20170101,
      20170101 to 20170101,
      0 to 4,
      "aa" :: "ab" :: "ba" :: "bb" :: Nil)
  }

  test("getPartitionsByFilter: ds=(20170101 + 1) and h=0") {
    // Should return all partitions where h=0 because getPartitionsByFilter does not support
    // comparisons to non-literal values
    testMetastorePartitionFiltering(
      attr("ds") === (Literal(20170101) + 1) && attr("h") === 0,
      20170101 to 20170103,
      0 to 0,
      "aa" :: "ab" :: "ba" :: "bb" :: Nil)
  }

  test("getPartitionsByFilter: chunk='aa'") {
    testMetastorePartitionFiltering(
      attr("chunk") === "aa",
      20170101 to 20170103,
      0 to 4,
      "aa" :: Nil)
  }

  test("getPartitionsByFilter: cast(chunk as int)=1 (not a valid partition predicate)") {
    testMetastorePartitionFiltering(
      attr("chunk").cast(IntegerType) === 1,
      20170101 to 20170103,
      0 to 4,
      "aa" :: "ab" :: "ba" :: "bb" :: Nil)
  }

  test("getPartitionsByFilter: cast(chunk as boolean)=true (not a valid partition predicate)") {
    testMetastorePartitionFiltering(
      attr("chunk").cast(BooleanType) === true,
      20170101 to 20170103,
      0 to 4,
      "aa" :: "ab" :: "ba" :: "bb" :: Nil)
  }

  test("getPartitionsByFilter: 20170101=ds") {
    testMetastorePartitionFiltering(
      Literal(20170101) === attr("ds"),
      20170101 to 20170101,
      0 to 4,
      "aa" :: "ab" :: "ba" :: "bb" :: Nil)
  }

  test("getPartitionsByFilter: ds=20170101 and h=2") {
    testMetastorePartitionFiltering(
      attr("ds") === 20170101 && attr("h") === 2,
      20170101 to 20170101,
      2 to 2,
      "aa" :: "ab" :: "ba" :: "bb" :: Nil)
  }

  test("getPartitionsByFilter: cast(ds as long)=20170101L and h=2") {
    testMetastorePartitionFiltering(
      attr("ds").cast(LongType) === 20170101L && attr("h") === 2,
      20170101 to 20170101,
      2 to 2,
      "aa" :: "ab" :: "ba" :: "bb" :: Nil)
  }

  test("getPartitionsByFilter: ds=20170101 or ds=20170102") {
    testMetastorePartitionFiltering(
      attr("ds") === 20170101 || attr("ds") === 20170102,
      20170101 to 20170102,
      0 to 4,
      "aa" :: "ab" :: "ba" :: "bb" :: Nil)
  }

  test("getPartitionsByFilter: ds in (20170102, 20170103) (using IN expression)") {
    testMetastorePartitionFiltering(
      attr("ds").in(20170102, 20170103),
      20170102 to 20170103,
      0 to 4,
      "aa" :: "ab" :: "ba" :: "bb" :: Nil)
  }

  test("getPartitionsByFilter: cast(ds as long) in (20170102L, 20170103L) (using IN expression)") {
    testMetastorePartitionFiltering(
      attr("ds").cast(LongType).in(20170102L, 20170103L),
      20170102 to 20170103,
      0 to 4,
      "aa" :: "ab" :: "ba" :: "bb" :: Nil)
  }

  test("getPartitionsByFilter: ds in (20170102, 20170103) (using INSET expression)") {
    testMetastorePartitionFiltering(
      attr("ds").in(20170102, 20170103),
      20170102 to 20170103,
      0 to 4,
      "aa" :: "ab" :: "ba" :: "bb" :: Nil, {
        case expr @ In(v, list) if expr.inSetConvertible =>
          InSet(v, list.map(_.eval(EmptyRow)).toSet)
      })
  }

  test("getPartitionsByFilter: cast(ds as long) in (20170102L, 20170103L) (using INSET expression)")
  {
    testMetastorePartitionFiltering(
      attr("ds").cast(LongType).in(20170102L, 20170103L),
      20170102 to 20170103,
      0 to 4,
      "aa" :: "ab" :: "ba" :: "bb" :: Nil, {
        case expr @ In(v, list) if expr.inSetConvertible =>
          InSet(v, list.map(_.eval(EmptyRow)).toSet)
      })
  }

  test("getPartitionsByFilter: chunk in ('ab', 'ba') (using IN expression)") {
    testMetastorePartitionFiltering(
      attr("chunk").in("ab", "ba"),
      20170101 to 20170103,
      0 to 4,
      "ab" :: "ba" :: Nil)
  }

  test("getPartitionsByFilter: chunk in ('ab', 'ba') (using INSET expression)") {
    testMetastorePartitionFiltering(
      attr("chunk").in("ab", "ba"),
      20170101 to 20170103,
      0 to 4,
      "ab" :: "ba" :: Nil, {
        case expr @ In(v, list) if expr.inSetConvertible =>
          InSet(v, list.map(_.eval(EmptyRow)).toSet)
      })
  }

  test("getPartitionsByFilter: (ds=20170101 and h>=2) or (ds=20170102 and h<2)") {
    val day1 = (20170101 to 20170101, 2 to 4, Seq("aa", "ab", "ba", "bb"))
    val day2 = (20170102 to 20170102, 0 to 1, Seq("aa", "ab", "ba", "bb"))
    testMetastorePartitionFiltering((attr("ds") === 20170101 && attr("h") >= 2) ||
        (attr("ds") === 20170102 && attr("h") < 2), day1 :: day2 :: Nil)
  }

  test("getPartitionsByFilter: (ds=20170101 and h>=2) or (ds=20170102 and h<(1+1))") {
    val day1 = (20170101 to 20170101, 2 to 4, Seq("aa", "ab", "ba", "bb"))
    // Day 2 should include all hours because we can't build a filter for h<(7+1)
    val day2 = (20170102 to 20170102, 0 to 4, Seq("aa", "ab", "ba", "bb"))
    testMetastorePartitionFiltering((attr("ds") === 20170101 && attr("h") >= 2) ||
        (attr("ds") === 20170102 && attr("h") < (Literal(1) + 1)), day1 :: day2 :: Nil)
  }

  test("getPartitionsByFilter: " +
      "chunk in ('ab', 'ba') and ((ds=20170101 and h>=2) or (ds=20170102 and h<2))") {
    val day1 = (20170101 to 20170101, 2 to 4, Seq("ab", "ba"))
    val day2 = (20170102 to 20170102, 0 to 1, Seq("ab", "ba"))
    testMetastorePartitionFiltering(attr("chunk").in("ab", "ba") &&
        ((attr("ds") === 20170101 && attr("h") >= 2) || (attr("ds") === 20170102 && attr("h") < 2)),
      day1 :: day2 :: Nil)
  }

  test("getPartitionsByFilter: chunk contains bb") {
    testMetastorePartitionFiltering(
      attr("chunk").contains("bb"),
      (20170101 to 20170103, 0 to 4, Seq("bb")) :: Nil)
  }

  test("getPartitionsByFilter: chunk startsWith b") {
    testMetastorePartitionFiltering(
      attr("chunk").startsWith("b"),
      (20170101 to 20170103, 0 to 4, Seq("ba", "bb")) :: Nil)
  }

  test("getPartitionsByFilter: chunk endsWith b") {
    testMetastorePartitionFiltering(
      attr("chunk").endsWith("b"),
      (20170101 to 20170103, 0 to 4, Seq("ab", "bb")) :: Nil)
  }

  test("getPartitionsByFilter: chunk in ('ab', 'ba') and ((cast(ds as string)>'20170102')") {
    val day = (20170101 to 20170103, 0 to 4, Seq("ab", "ba"))
    testMetastorePartitionFiltering(
      attr("chunk").in("ab", "ba") && (attr("ds").cast(StringType) > "20170102"),
      day :: Nil)
  }

  test("getPartitionsByFilter: date type pruning by metastore") {
    val table = CatalogTable(
      identifier = TableIdentifier("test_date", Some("default")),
      tableType = CatalogTableType.MANAGED,
      schema = new StructType().add("value", "int").add("part", "date"),
      partitionColumnNames = Seq("part"),
      storage = storageFormat)
    client.createTable(table, ignoreIfExists = false)

    val partitions =
      for {
        date <- Seq("2019-01-01", "2019-01-02", "2019-01-03", "2019-01-04")
      } yield CatalogTablePartition(Map(
        "part" -> date
      ), storageFormat)
    assert(partitions.size == 4)

    client.createPartitions("default", "test_date", partitions, ignoreIfExists = false)

    def testDataTypeFiltering(
        filterExprs: Seq[Expression],
        expectedPartitionCubes: Seq[Seq[Date]]): Unit = {
      val filteredPartitions = client.getPartitionsByFilter(
        client.getTable("default", "test_date"),
        filterExprs,
        SQLConf.get.sessionLocalTimeZone)

      val expectedPartitions = expectedPartitionCubes.map {
        expectedDt =>
          for {
            dt <- expectedDt
          } yield Set(
            "part" -> dt.toString
          )
      }.reduce(_ ++ _)

      assert(filteredPartitions.map(_.spec.toSet).toSet == expectedPartitions.toSet)
    }

    val dateAttr: Attribute = AttributeReference("part", DateType)()

    testDataTypeFiltering(
      Seq(dateAttr === Date.valueOf("2019-01-01")),
      Seq("2019-01-01").map(Date.valueOf) :: Nil)
    testDataTypeFiltering(
      Seq(dateAttr > Date.valueOf("2019-01-02")),
      Seq("2019-01-03", "2019-01-04").map(Date.valueOf) :: Nil)
    testDataTypeFiltering(
      Seq(In(dateAttr,
        Seq("2019-01-01", "2019-01-02").map(d => Literal(Date.valueOf(d))))),
      Seq("2019-01-01", "2019-01-02").map(Date.valueOf) :: Nil)
    testDataTypeFiltering(
      Seq(InSet(dateAttr,
        Set("2019-01-01", "2019-01-02").map(d => Literal(Date.valueOf(d)).eval(EmptyRow)))),
      Seq("2019-01-01", "2019-01-02").map(Date.valueOf) :: Nil)
  }

  private def testMetastorePartitionFiltering(
      filterExpr: Expression,
      expectedDs: Seq[Int],
      expectedH: Seq[Int],
      expectedChunks: Seq[String]): Unit = {
    testMetastorePartitionFiltering(
      filterExpr,
      (expectedDs, expectedH, expectedChunks) :: Nil,
      identity)
  }

  private def testMetastorePartitionFiltering(
      filterExpr: Expression,
      expectedDs: Seq[Int],
      expectedH: Seq[Int],
      expectedChunks: Seq[String],
      transform: Expression => Expression): Unit = {
    testMetastorePartitionFiltering(
      filterExpr,
      (expectedDs, expectedH, expectedChunks) :: Nil,
      transform)
  }

  private def testMetastorePartitionFiltering(
      filterExpr: Expression,
      expectedPartitionCubes: Seq[(Seq[Int], Seq[Int], Seq[String])]): Unit = {
    testMetastorePartitionFiltering(filterExpr, expectedPartitionCubes, identity)
  }

  private def testMetastorePartitionFiltering(
      filterExpr: Expression,
      expectedPartitionCubes: Seq[(Seq[Int], Seq[Int], Seq[String])],
      transform: Expression => Expression): Unit = {
    val filteredPartitions = client.getPartitionsByFilter(client.getTable("default", "test"),
      Seq(
        transform(filterExpr)
      ), SQLConf.get.sessionLocalTimeZone)

    val expectedPartitionCount = expectedPartitionCubes.map {
      case (expectedDs, expectedH, expectedChunks) =>
        expectedDs.size * expectedH.size * expectedChunks.size
    }.sum

    val expectedPartitions = expectedPartitionCubes.map {
      case (expectedDs, expectedH, expectedChunks) =>
        for {
          ds <- expectedDs
          h <- expectedH
          chunk <- expectedChunks
        } yield Set(
          "ds" -> ds.toString,
          "h" -> h.toString,
          "chunk" -> chunk
        )
    }.reduce(_ ++ _)

    val actualFilteredPartitionCount = filteredPartitions.size

    assert(actualFilteredPartitionCount == expectedPartitionCount,
      s"Expected $expectedPartitionCount partitions but got $actualFilteredPartitionCount")
    assert(filteredPartitions.map(_.spec.toSet).toSet == expectedPartitions.toSet)
  }
}