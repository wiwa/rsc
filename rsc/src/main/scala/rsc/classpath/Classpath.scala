// Copyright (c) 2017-2019 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
// NOTE: This file has been partially copy/pasted from scalameta/scalameta.
package rsc.classpath

import java.nio.file._
import java.util
import java.util.HashMap
import rsc.classpath.javacp._
import rsc.classpath.scalacp._
import rsc.report.VerboseMessage
import rsc.semantics._
import rsc.util._
import scala.collection.mutable
import scala.meta.internal.{semanticdb => s}
import scala.meta.internal.semanticdb.{Language => l}
// import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation.{Kind => k}
import scala.meta.scalasig._
import scala.meta.scalasig.lowlevel._

final class Classpath private (index: Index) extends AutoCloseable {
  var time = 0L
  private val infos = new HashMap[String, s.SymbolInformation]
  private val pathsDone = new mutable.HashSet[String]
  Scalalib.synthetics.foreach(info => infos.put(info.symbol, info))
  Scalalib.packages.foreach(info => infos.put(info.symbol, info))
  println("asdf", infos.containsKey("scala/AnyRef#"))

  def contains(sym: String): Boolean = this.synchronized {
    if (infos.containsKey(sym)) {
      true
    } else {
      load(sym)
      infos.containsKey(sym)
    }
  }

  def apply(sym: String): s.SymbolInformation = this.synchronized {
    val info = infos.get(sym)
    if (info != null) {
      info
    } else {
      load(sym)
      val info = infos.get(sym)
      if (info != null) info
      else {
        println("wtf", s"\'$sym\'", infos.containsKey(sym))
        crash(sym)
      }
    }
  }

  private def load(sym: String): Unit = this.synchronized {
//    if (sym == "scala/AnyRef#") {
//      println("fdsa")
//    }
    val info = infos.get(sym)
    if (info == null) {
      if (sym.hasLoc) {
        if (index.contains(sym.metadataLoc)) {
          index(sym.metadataLoc) match {
            case PackageEntry() =>
              val info = s.SymbolInformation(
                symbol = sym,
                language = l.SCALA,
                kind = k.PACKAGE,
                displayName = sym.desc.value
              )
              infos.put(info.symbol, info)
            case entry: FileEntry =>
              val binary = {
                val stream = entry.openStream()
                try BytesBinary(entry.str, stream.readAllBytes())
                finally stream.close()
              }
              val payload = Scalasig.fromBinary(binary) match {
                case FailedClassfile(_, cause) => crash(cause)
                case FailedScalasig(_, _, cause) => crash(cause)
                case EmptyScalasig(_, Classfile(_, _, JavaPayload(node))) => Javacp.parse(node, index)
                case EmptyScalasig(_, Classfile(name, _, _)) => crash(name)
                case ParsedScalasig(_, _, scalasig) => Scalacp.parse(scalasig, index)
              }
              payload.foreach(info => infos.put(info.symbol, info))
          }
        }
      } else {
        if (sym.owner != "") load(sym.owner)
        else ()
      }
    }
//    else {
//      if (sym == "scala/AnyRef#") {
//        println("fdsa2")
//      }
//    }
  }

  def close(): Unit = this.synchronized {
    index.close()
  }

  def go(paths: List[Path]): Unit = this.synchronized {
    val s = System.nanoTime()
    val paths1 = paths.filterNot(p => pathsDone.contains(p.toString))
    index.go(paths1)
    paths1.foreach(p => pathsDone.add(p.toString))
    val e = System.nanoTime()
    val elapsed = ((e - s) / 1000000)
    time += elapsed

    println(s"Finished indexgo in $elapsed ms")
  }
}

object Classpath {
  def apply(paths: List[Path]): Classpath = {
    println(paths.map(_.toAbsolutePath.toString).mkString(":"))
    val index = Index(paths, new HashMap[Locator, Entry]())
    new Classpath(index)
  }
}
