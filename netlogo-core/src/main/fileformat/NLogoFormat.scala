// (C) Uri Wilensky. https://github.com/NetLogo/NetLogo

package org.nlogo.fileformat

import java.net.URI
import java.nio.file.{ Files, Paths }

import org.nlogo.core.{ Femto, LiteralParser, Model, Shape, ShapeParser, View, Widget }, Shape.{ LinkShape, VectorShape }
import org.nlogo.core.model.WidgetReader
import org.nlogo.api.{ ComponentSerialization, FileIO, ModelFormat, Version, VersionHistory }
import scala.util.{ Failure, Success, Try }
import scala.io.Source

class NLogoFormat(val autoConvert: String => String => String)
  extends ModelFormat[Array[String], NLogoFormat]
  with AbstractNLogoFormat[NLogoFormat] {
    def name: String = "nlogo"
    def widgetReaders: Map[String, WidgetReader] = Map()
  }

trait AbstractNLogoFormat[A <: ModelFormat[Array[String], A]] {
  def autoConvert: String => String => String
  val Separator = "@#$#@#$#@"
  val SeparatorRegex = "@#\\$#@#\\$#@"

  def widgetReaders: Map[String, WidgetReader]

  def sections(location: URI) =
    Try {
      if (location.getScheme == "jar") Source.fromInputStream(location.toURL.openStream)
      else Source.fromURI(location)
      }.flatMap { s =>
        val sections = sectionsFromSource(s.mkString)
        s.close()
        sections
      }

  lazy val sectionNames =
    Seq("code",
      "interface",
      "info",
      "turtleshapes",
      "version",
      "previewcommands",
      "systemdynamics",
      "behaviorspace",
      "hubnetclient",
      "linkshapes",
      "modelsettings",
      "deltatick").map(s => "org.nlogo.modelsection." + s)

  def writeSections(sections: Map[String, Array[String]], location: URI): Try[URI] = {
    Try(Paths.get(location)).flatMap { filePath =>
      sectionsToSource(sections).flatMap { fileText =>
        Try {
          val writer = Files.newBufferedWriter(filePath)
          try {
            writer.write(fileText)
            writer.flush()
            location
          }
          finally {
            writer.close
          }
        }
      }
    }
  }

  def sectionsToSource(sections: Map[String, Array[String]]): Try[String] = {
    Try(
      sectionNames.map { name =>
        val sectionLines = sections.getOrElse(name, Array[String]())
        if (sectionLines.isEmpty)
          "\n"
        else if (sectionLines.head.isEmpty || sectionLines.head.startsWith("\n") || name == "org.nlogo.modelsection.code")
          sectionLines.mkString("", "\n", "\n")
        else
          sectionLines.mkString("\n", "\n", "\n")
      }.mkString(Separator))
  }

  def sectionsFromSource(source: String): Try[Map[String, Array[String]]] = {
    try {
      val sectionLines = source
        .split(SeparatorRegex)
        .map(_.lines.toSeq)
        .map(s => if (s.headOption.contains("")) s.tail else s)
        .map(_.toArray)
        Success((sectionNames zip sectionLines).toMap)
    }
    catch {
      case ex: Exception => Failure(ex)
    }
  }

  object CodeComponent extends ComponentSerialization[Array[String], A] {
    val componentName = "org.nlogo.modelsection.code"
    override def addDefault = ((m: Model) => m.copy(code = ""))
    def serialize(m: Model): Array[String] = m.code.lines.map(_.replaceAll("\\s*$", "")).toArray
    def validationErrors(m: Model): Option[String] = None
    override def deserialize(lines: Array[String]) = { (m: Model) =>
      m.copy(code = autoConvert(m.version)(lines.mkString("\n")))
    }
  }

  object InfoComponent extends ComponentSerialization[Array[String], A] {
    val componentName = "org.nlogo.modelsection.info"
    val EmptyInfoPath = "/system/empty-info.md"
    override def addDefault =
      ((m: Model) => m.copy(info = FileIO.url2String(EmptyInfoPath)))
    def serialize(m: Model): Array[String] = m.info.lines.toArray
    def validationErrors(m: Model): Option[String] = None
    override def deserialize(s: Array[String]) = {(m: Model) =>
      val finalInfo =
        if (VersionHistory.olderThan42pre2(m.version))
          InfoConverter.convert(s.mkString("\n"))
        else
          s.mkString("\n")
      m.copy(info = finalInfo)
    }
  }

  object VersionComponent extends ComponentSerialization[Array[String], A] {
    val componentName = "org.nlogo.modelsection.version"
    override def addDefault = (_.copy(version = Version.version))
    def serialize(m: Model): Array[String] = Array(m.version)
    def validationErrors(m: Model): Option[String] = None
    override def deserialize(s: Array[String]) = { (m: Model) =>
      m.copy(version = s.mkString.trim)
    }
  }

  object InterfaceComponent extends ComponentSerialization[Array[String], A] {
    import org.nlogo.fileformat
    val componentName = "org.nlogo.modelsection.interface"
    private val additionalReaders = AbstractNLogoFormat.this.widgetReaders
    private val literalParser = Femto.scalaSingleton[LiteralParser]("org.nlogo.parse.CompilerUtilities")
    override def addDefault = _.copy(widgets = Seq(View()))

    def serialize(m: Model): Array[String] =
      m.widgets.flatMap((w: Widget) => (WidgetReader.format(w, additionalReaders).lines.toSeq :+ "")).toArray

    def validationErrors(m: Model): Option[String] = None

    private def parseWidgets(lines: Array[String]): List[List[String]] = {
      val widgets = new collection.mutable.ListBuffer[List[String]]
      val widget = new collection.mutable.ListBuffer[String]
      for(line <- lines)
        if(line.nonEmpty)
          widget += line
        else {
          if(!widget.forall(_.isEmpty))
            widgets += widget.toList
          widget.clear()
        }
        if(!widget.isEmpty)
          widgets += widget.toList
        widgets.toList
    }

    override def deserialize(s: Array[String]) = {(m: Model) =>
      val widgets = parseWidgets(s)
      m.copy(
        widgets = widgets.map(w =>
            WidgetReader.read(w.toList, literalParser, additionalReaders))
              .map(_.convertSource(autoConvert(m.version))))
    }
  }

  object VectorShapesComponent extends ComponentSerialization[Array[String], A] {
    val componentName = "org.nlogo.modelsection.turtleshapes"
    override def addDefault = _.copy(turtleShapes = Model.defaultShapes)
    def serialize(m: Model): Array[String] =
      ShapeParser.formatVectorShapes(m.turtleShapes).lines.toArray
    def validationErrors(m: Model): Option[String] = None
    override def deserialize(s: Array[String]) = {(m: Model) =>
      if (s.isEmpty) addDefault(m)
      else m.copy(turtleShapes = ShapeParser.parseVectorShapes(s))
    }
  }

  object LinkShapesComponent extends ComponentSerialization[Array[String], A] {
    val componentName = "org.nlogo.modelsection.linkshapes"
    override def addDefault = ((m: Model) => m.copy(linkShapes = Model.defaultLinkShapes.toSeq))
    def serialize(m: Model): Array[String] = ShapeParser.formatLinkShapes(m.linkShapes).lines.toArray
    def validationErrors(m: Model): Option[String] = None
    override def deserialize(s: Array[String]) = { (m: Model) =>
      if (s.isEmpty) addDefault(m)
      else m.copy(linkShapes = ShapeParser.parseLinkShapes(s))
    }
  }

  def codeComponent       = CodeComponent
  def infoComponent       = InfoComponent
  def interfaceComponent  = InterfaceComponent
  def version             = VersionComponent
  def shapesComponent     = VectorShapesComponent
  def linkShapesComponent = LinkShapesComponent
}
