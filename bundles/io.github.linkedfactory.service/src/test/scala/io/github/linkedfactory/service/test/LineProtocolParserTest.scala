/*
 * Copyright (c) 2022 Fraunhofer IWU.
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
package io.github.linkedfactory.service.test

import io.github.linkedfactory.service.util.{LineProtocolParser, Parser, Tokenizer}
import net.liftweb.common.Failure
import org.junit.Test
import org.junit.Assert
import net.enilink.komma.core.URIs

object LineProtocolParserTest {
  def main(args: Array[String]) = new LineProtocolParserTest().benchmark(args)
}

class LineProtocolParserTest {

  @Test
  def test = {
    val p = new Parser
    val time = System.currentTimeMillis

    // int value
    val d1 = p.parse("""http://example.org/intProperty,item=http://example.org/item value=42i""", time)
    Assert.assertEquals(URIs.createURI("http://example.org/item"), d1._1)
    Assert.assertEquals(URIs.createURI("http://example.org/intProperty"), d1._2)
    // timestamp not given, should be set to current time
    Assert.assertEquals(time, d1._3)
    Assert.assertEquals(42, d1._4)

    // string value with escaped characters
    val d2 = p.parse("http://example.org/stringProperty,item=http://example.org/item value=\"escaped\\ characters:\\\t\\ \\\"\\=\\,\"", time)
    Assert.assertEquals(URIs.createURI("http://example.org/item"), d2._1)
    Assert.assertEquals(URIs.createURI("http://example.org/stringProperty"), d2._2)
    // timestamp not given, should be set to current time
    Assert.assertEquals(time, d2._3)
    Assert.assertEquals("escaped characters:\t \"=,", d2._4)

    // double value (default) and timestamp in ns
    val d3 = p.parse("""http://example.org/property\,type\=Double,item=http://example.org/item value=23 1529592952925259295""", time)
    Assert.assertEquals(URIs.createURI("http://example.org/item"), d3._1)
    Assert.assertEquals(URIs.createURI("http://example.org/property,type=Double"), d3._2)
    // timestamp given (in nanoseconds)
    Assert.assertEquals(1529592952925259295L / 1000 / 1000, d3._3) // ns -> ms
    Assert.assertEquals(23.0, d3._4)

    // boolean value (false) and additional tag b, field d
    // FIXME: additional tags and fields are parsed, but ignored
    val d4 = p.parse("""a,item=http://example.org/item,b=c value=f,d=t""", time)
    Assert.assertEquals(URIs.createURI("http://example.org/item"), d4._1)
    Assert.assertEquals(URIs.createURI("a"), d4._2)
   // timestamp not given, should be set to current time
    Assert.assertEquals(time, d4._3)
    Assert.assertEquals(false, d4._4)
  }

  // simple benchmark: regex vs. tokenizer vs. parser
  def benchmark(args: Array[String]) = {
    // InfluxDB line protocol: measurement[,tag_set] field_set [timestamp]
    // simple RegEx for performance comparison (no support for quotes or multiple fields/tags)
    val LinePattern = """^([^,]+),item=([\S]+)\s+value=([\S]+)(\s+[\d]+)?\s*$""".r

    val iterations = 100000
    val warmupRuns = 100000
    //val l = """a,item=http://example.org/item,b=c value=42i,d=f 1529592952925259295"""
    //val l = """http://example.org/prop\=erty,item=http://example.org/item value="escaped\ comment:\ \=\"\," 1529592952925259295"""
    val l = """http://example.org/property,item=http://example.org/item value=42i 1529592952925259295"""
    //val l = """http://example.org/property,item=http://example.org/item value=42i"""

    val p = new Parser

    // HOTspot warm-up
    for (i <- 1 to warmupRuns) {
      // tokenizer
      val t = new Tokenizer(l)
      while (t.hasNextToken) {
        val d = t.nextToken
        if (i == 1) println("tokenizer: " + d)
      }
      // parser
      val d = p.parse(l)
      if (i == 1) println("parser: " + d)
      // regex
      l match {
        case LinePattern(property, item, value, time) =>
          val d = LineProtocolParser.mkItemData(property, item, value, time)
          if (i == 1) println("regex: " + d)
        case o @ _ => Failure("Invalid line: " + o)
      }
    }

    // measured run
    var time = System.currentTimeMillis
    for (i <- 1 to iterations) {
      val t = new Tokenizer(l)
      while (t.hasNextToken) {
        t.nextToken
      }
    }
    println(iterations + " (Tokenizer): " + (System.currentTimeMillis - time) + "ms")

    time = System.currentTimeMillis
    for (i <- 1 to iterations) {
      p.parse(l)
    }
    println(iterations + " (Parser): " + (System.currentTimeMillis - time) + "ms")

    time = System.currentTimeMillis
    for (i <- 1 to iterations) {
      l match {
        case LinePattern(property, item, value, time) => LineProtocolParser.mkItemData(property, item, value, time)
        case o @ _ => Failure("Invalid line: " + o)
      }
    }
    println(iterations + " (RegEx): " + (System.currentTimeMillis - time) + "ms")
  }
}
