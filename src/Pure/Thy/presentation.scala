/*  Title:      Pure/Thy/presentation.scala
    Author:     Makarius

HTML presentation of PIDE document content.
*/

package isabelle


import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.collection.mutable


object Presentation {
  /** HTML documents **/

  /* HTML context */

  def html_context(
    sessions_structure: Sessions.Structure,
    elements: Elements,
    root_dir: Path = Path.current,
    nodes: Nodes = Nodes.empty
  ): HTML_Context = new HTML_Context(sessions_structure, elements, root_dir, nodes)

  class HTML_Context private[Presentation](
    sessions_structure: Sessions.Structure,
    val elements: Elements,
    val root_dir: Path,
    val nodes: Nodes
  ) {
    /* directory structure and resources */

    def theory_by_name(session: String, theory: String): Option[Nodes.Theory] =
      nodes.theory_by_name(session, theory)

    def theory_by_file(session: String, file: String): Option[Nodes.Theory] =
      nodes.theory_by_file(session, file)

    def session_dir(session: String): Path =
      root_dir + Path.explode(sessions_structure(session).chapter_session)

    def theory_html(theory: Nodes.Theory): Path =
      Path.explode(theory.print_short).html

    def file_html(file: String): Path =
      Path.explode("files") + Path.explode(file).squash.html

    def smart_html(theory: Nodes.Theory, file: String): Path =
      if (file.endsWith(".thy")) theory_html(theory) else file_html(file)

    def files_path(session: String, path: Path): Path =
      session_dir(session) + Path.explode("files") + path.squash.html

    def relative_link(dir: Path, file: Path): String =
      try { File.path(dir.java_path.relativize(file.java_path).toFile).implode }
      catch {
        case _: IllegalArgumentException =>
          error("Cannot relativize " + file + " wrt. " + dir)
      }


    /* HTML content */

    def head(title: String, rest: XML.Body = Nil): XML.Tree =
      HTML.div("head", HTML.chapter(title) :: rest)

    def source(body: XML.Body): XML.Tree = HTML.pre("source", body)

    def contents(
      heading: String,
      items: List[XML.Body],
      css_class: String = "contents"
    ) : List[XML.Elem] = {
      if (items.isEmpty) Nil
      else List(HTML.div(css_class, List(HTML.section(heading), HTML.itemize(items))))
    }

    val isabelle_css: String = File.read(HTML.isabelle_css)

    def html_document(title: String, body: XML.Body, fonts_css: String): HTML_Document = {
      val content =
        HTML.output_document(
          List(
            HTML.style(fonts_css + "\n\n" + isabelle_css),
            HTML.title(title)),
          List(HTML.source(body)), css = "", structural = false)
      HTML_Document(title, content)
    }
  }

  sealed case class HTML_Document(title: String, content: String)


  /* presentation elements */

  sealed case class Elements(
    html: Markup.Elements = Markup.Elements.empty,
    entity: Markup.Elements = Markup.Elements.empty,
    language: Markup.Elements = Markup.Elements.empty)

  val elements1: Elements =
    Elements(
      html = Rendering.foreground_elements ++ Rendering.text_color_elements +
        Markup.NUMERAL + Markup.COMMENT + Markup.ENTITY + Markup.LANGUAGE,
      entity = Markup.Elements(Markup.THEORY, Markup.TYPE_NAME, Markup.CONSTANT, Markup.FACT,
        Markup.CLASS, Markup.LOCALE, Markup.FREE))

  val elements2: Elements =
    Elements(
      html = elements1.html ++ Rendering.markdown_elements,
      language = Markup.Elements(Markup.Language.DOCUMENT))


  /* per-session node info */

  object Nodes {
    sealed case class Session(
      name: String,
      used_theories: List[String],
      loaded_theories: Map[String, Theory])

    object Theory {
      def apply(
        name: String,
        files: List[String],
        static_session: String,
        dynamic_session: String,
        entities: List[Export_Theory.Entity0],
        others: List[String]
      ): Theory = {
        val entities1 =
          entities.filter(e => e.file.nonEmpty && Position.Range.unapply(e.pos).isDefined)
        new Theory(name, files, static_session, dynamic_session, entities1, others)
      }
    }

    class Theory private(
      val name: String,
      val files: List[String],
      val static_session: String,
      val dynamic_session: String,
      entities: List[Export_Theory.Entity0],
      others: List[String]
    ) {
      override def toString: String = name

      val (thy_file, blobs_files) =
        files match {
          case Nil => error("Unknown theory file for " + quote(name))
          case a :: bs =>
            def for_theory: String = " for theory " + quote(name)
            if (!a.endsWith(".thy")) error("Bad .thy file " + quote(a) + for_theory)
            for (b <- bs if b.endsWith(".thy")) error("Bad auxiliary file " + quote(b) + for_theory)
            (a, bs)
        }

      def home_session: Boolean = static_session == dynamic_session

      def print_short: String =
        if (home_session) Long_Name.base_name(name) else name

      def print_long: String =
        "theory " + quote(name) +
        (if (home_session) "" else " (session " + quote(dynamic_session) + ")")

      private lazy val by_file_range: Map[(String, Symbol.Range), List[Export_Theory.Entity0]] =
        entities.groupBy(entity => (entity.file, entity.range))

      private lazy val by_file_kname: Map[(String, String), Export_Theory.Entity0] =
        (for {
          entity <- entities
          file <- Position.File.unapply(entity.pos)
        } yield (file, entity.kname) -> entity).toMap

      def get_defs(file: String, range: Symbol.Range): List[Export_Theory.Entity0] =
        by_file_range.getOrElse((file, range), Nil)

      def get_def(file: String, kind: String, name: String): Option[Export_Theory.Entity0] =
        by_file_kname.get((file, Export_Theory.export_kind_name(kind, name)))

      def elements(elements: Elements): Elements =
        elements.copy(entity = others.foldLeft(elements.entity)(_ + _))
    }

    val empty: Nodes = new Nodes(Map.empty)

    def read(
      database_context: Export.Database_Context,
      deps: Sessions.Deps,
      sessions: List[String]
    ): Nodes = {
      val sessions_domain = sessions.toSet
      val sessions_structure = deps.sessions_structure
      val sessions_requirements = sessions_structure.build_requirements(sessions)

      def read_theory(theory_context: Export.Theory_Context): Nodes.Theory =
      {
        val session_name = theory_context.session_context.session_name
        val theory_name = theory_context.theory

        val files = theory_context.files0(permissive = true)

        val (entities, others) =
          if (sessions_domain(session_name)) {
            val theory = Export_Theory.read_theory(theory_context, permissive = true)
            (theory.entity_iterator.toList,
             theory.others.keySet.toList)
          }
          else (Nil, Nil)

        Theory(theory_name,
          static_session = sessions_structure.theory_qualifier(theory_name),
          dynamic_session = session_name,
          files = files,
          entities = entities,
          others = others)
      }

      def read_session(session_name: String): Nodes.Session = {
        val used_theories = deps(session_name).used_theories.map(_._1.theory)
        val loaded_theories0 =
          using(database_context.open_session(deps.base_info(session_name))) { session_context =>
            for (theory_name <- used_theories)
              yield theory_name -> read_theory(session_context.theory(theory_name))
          }
        Session(session_name, used_theories, loaded_theories0.toMap)
      }

      val result0 =
        (for (session <- Par_List.map(read_session, sessions_requirements).iterator)
          yield session.name -> session).toMap

      val result1 =
        sessions_requirements.foldLeft(Map.empty[String, Session]) {
          case (seen, session_name) =>
            val session0 = result0(session_name)
            val loaded_theories1 =
              sessions_structure(session_name).parent.map(seen) match {
                case None => session0.loaded_theories
                case Some(parent_session) =>
                  parent_session.loaded_theories ++ session0.loaded_theories
              }
            val session1 = session0.copy(loaded_theories = loaded_theories1)
            seen + (session_name -> session1)
        }

      new Nodes(result1)
    }
  }

  class Nodes private(sessions: Map[String, Nodes.Session]) {
    override def toString: String =
      sessions.keysIterator.toList.sorted.mkString("Nodes(", ", ", ")")

    def the_session(session: String): Nodes.Session =
      sessions.getOrElse(session, error("Unknown session node information: " + quote(session)))

    def theory_by_name(session: String, theory: String): Option[Nodes.Theory] =
      by_session_and_theory_name.get((session, theory))

    def theory_by_file(session: String, file: String): Option[Nodes.Theory] =
      by_session_and_theory_file.get((session, file))

    private lazy val by_session_and_theory_name: Map[(String, String), Nodes.Theory] =
      (for {
        session <- sessions.valuesIterator
        theory <- session.loaded_theories.valuesIterator
      } yield (session.name, theory.name) -> theory).toMap

    private lazy val by_session_and_theory_file: Map[(String, String), Nodes.Theory] = {
      (for {
        session <- sessions.valuesIterator
        theory <- session.loaded_theories.valuesIterator
        file <- theory.files.iterator
      } yield (session.name, file) -> theory).toMap
    }
  }


  /* formal entities */

  object Theory_Ref {
    def unapply(props: Properties.T): Option[String] =
      (props, props) match {
        case (Markup.Kind(Markup.THEORY), Markup.Name(theory)) => Some(theory)
        case _ => None
      }
  }

  object Entity_Ref {
    def unapply(props: Properties.T): Option[(String, String, String)] =
      (props, props, props, props) match {
        case (Markup.Entity.Ref.Prop(_), Position.Def_File(file), Markup.Kind(kind), Markup.Name(name)) =>
          Some((file, kind, name))
        case _ => None
      }
  }

  object Entity_Context {
    val empty: Entity_Context = new Entity_Context

    def make(
      html_context: HTML_Context,
      session_name: String,
      theory_name: String,
      file_name: String
    ): Entity_Context =
      new Entity_Context {
        private val session_dir = html_context.session_dir(session_name)
        private val file_dir = Path.explode(file_name).dir

        private val seen_ranges: mutable.Set[Symbol.Range] = mutable.Set.empty

        override def make_def(range: Symbol.Range, body: XML.Body): Option[XML.Elem] =
          body match {
            case List(XML.Elem(Markup("span", List("id" -> _)), _)) => None
            case _ =>
              for (theory <- html_context.theory_by_name(session_name, theory_name))
              yield {
                val body1 =
                  if (seen_ranges.contains(range)) {
                    HTML.entity_def(HTML.span(HTML.id(offset_id(range)), body))
                  }
                  else HTML.span(body)
                theory.get_defs(file_name, range).foldLeft(body1) {
                  case (elem, entity) =>
                    HTML.entity_def(HTML.span(HTML.id(entity.kname), List(elem)))
                }
              }
          }

        private def offset_id(range: Text.Range): String =
          "offset_" + range.start + ".." + range.stop

        override def make_ref(props: Properties.T, body: XML.Body): Option[XML.Elem] = {
          props match {
            case Theory_Ref(thy_name) =>
              for (theory <- html_context.theory_by_name(session_name, thy_name))
              yield {
                val html_path = session_dir + html_context.theory_html(theory)
                val html_link = html_context.relative_link(file_dir, html_path)
                HTML.link(html_link, body)
              }
            case Entity_Ref(def_file, kind, name) =>
              def logical_ref(theory: Nodes.Theory): Option[String] =
                theory.get_def(def_file, kind, name).map(_.kname)

              def physical_ref(theory: Nodes.Theory): Option[String] =
                props match {
                  case Position.Def_Range(range) if theory.name == theory_name =>
                    seen_ranges += range
                    Some(offset_id(range))
                  case _ => None
                }

              for {
                theory <- html_context.theory_by_file(session_name, def_file)
                html_ref <- logical_ref(theory) orElse physical_ref(theory)
              }
              yield {
                val html_path = session_dir + html_context.smart_html(theory, def_file)
                val html_link = html_context.relative_link(file_dir, html_path)
                HTML.entity_ref(HTML.link(html_link + "#" + html_ref, body))
              }
            case _ => None
          }
        }
      }
  }

  class Entity_Context {
    def make_def(range: Symbol.Range, body: XML.Body): Option[XML.Elem] = None
    def make_ref(props: Properties.T, body: XML.Body): Option[XML.Elem] = None
  }


  /* HTML output */

  private val div_elements =
    Set(HTML.div.name, HTML.pre.name, HTML.par.name, HTML.list.name, HTML.`enum`.name,
      HTML.descr.name)

  def make_html(
    entity_context: Entity_Context,
    elements: Elements,
    xml: XML.Body
  ): XML.Body = {
    def html_div(html: XML.Body): Boolean =
      html exists {
        case XML.Elem(markup, body) => div_elements.contains(markup.name) || html_div(body)
        case XML.Text(_) => false
      }

    def html_class(c: String, html: XML.Body): XML.Body =
      if (c == "") html
      else if (html_div(html)) List(HTML.div(c, html))
      else List(HTML.span(c, html))

    def html_body(xml_body: XML.Body, end_offset: Symbol.Offset): (XML.Body, Symbol.Offset) =
      xml_body.foldRight((List.empty[XML.Tree], end_offset)) { case (tree, (res, end_offset1)) =>
        val (res1, offset) = html_body_single(tree, end_offset1)
        (res1 ++ res, offset)
      }

    @tailrec
    def html_body_single(xml_tree: XML.Tree, end_offset: Symbol.Offset): (XML.Body, Symbol.Offset) =
      xml_tree match {
        case XML.Wrapped_Elem(markup, _, body) => html_body_single(XML.Elem(markup, body), end_offset)
        case XML.Elem(Markup(Markup.ENTITY, props @ Markup.Kind(kind)), body) =>
          val (body1, offset) = html_body(body, end_offset)
          if (elements.entity(kind)) {
            entity_context.make_ref(props, body1) match {
              case Some(link) => (List(link), offset)
              case None => (body1, offset)
            }
          }
          else (body1, offset)
        case XML.Elem(Markup(Markup.LANGUAGE, Markup.Name(name)), body) =>
          val (body1, offset) = html_body(body, end_offset)
          (html_class(if (elements.language(name)) name else "", body1), offset)
        case XML.Elem(Markup(Markup.MARKDOWN_PARAGRAPH, _), body) =>
          val (body1, offset) = html_body(body, end_offset)
          (List(HTML.par(body1)), offset)
        case XML.Elem(Markup(Markup.MARKDOWN_ITEM, _), body) =>
          val (body1, offset) = html_body(body, end_offset)
          (List(HTML.item(body1)), offset)
        case XML.Elem(Markup(Markup.Markdown_Bullet.name, _), text) =>
          (Nil, end_offset - XML.symbol_length(text))
        case XML.Elem(Markup.Markdown_List(kind), body) =>
          val (body1, offset) = html_body(body, end_offset)
          if (kind == Markup.ENUMERATE) (List(HTML.`enum`(body1)), offset)
          else (List(HTML.list(body1)), offset)
        case XML.Elem(markup, body) =>
          val name = markup.name
          val (body1, offset) = html_body(body, end_offset)
          val html =
            markup.properties match {
              case Markup.Kind(kind) if kind == Markup.COMMAND || kind == Markup.KEYWORD =>
                html_class(kind, body1)
              case _ =>
                body1
            }
          Rendering.foreground.get(name) orElse Rendering.text_color.get(name) match {
            case Some(c) => (html_class(c.toString, html), offset)
            case None => (html_class(name, html), offset)
          }
        case XML.Text(text) =>
          val offset = end_offset - Symbol.length(text)
          val body = HTML.text(Symbol.decode(text))
          entity_context.make_def(Text.Range(offset, end_offset), body) match {
            case Some(body1) => (List(body1), offset)
            case None => (body, offset)
          }
      }

    html_body(xml, XML.symbol_length(xml) + 1)._1
  }


  /* PIDE HTML document */

  def html_document(
    snapshot: Document.Snapshot,
    html_context: HTML_Context,
    plain_text: Boolean = false,
    fonts_css: String = HTML.fonts_css()
  ): HTML_Document = {
    require(!snapshot.is_outdated, "document snapshot outdated")

    val name = snapshot.node_name
    if (plain_text) {
      val title = "File " + Symbol.cartouche_decoded(name.path.file_name)
      val body = HTML.text(snapshot.node.source)
      html_context.html_document(title, body, fonts_css)
    }
    else {
      Resources.html_document(snapshot) getOrElse {
        val title =
          if (name.is_theory) "Theory " + quote(name.theory_base_name)
          else "File " + Symbol.cartouche_decoded(name.path.file_name)
        val xml = snapshot.xml_markup(elements = html_context.elements.html)
        val body = make_html(Entity_Context.empty, html_context.elements, xml)
        html_context.html_document(title, body, fonts_css)
      }
    }
  }



  /** HTML presentation **/

  /* presentation context */

  object Context {
    val none: Context = new Context { def enabled: Boolean = false }
    val standard: Context = new Context { def enabled: Boolean = true }

    def dir(path: Path): Context =
      new Context {
        def enabled: Boolean = true
        override def dir(store: Sessions.Store): Path = path
      }

    def make(s: String): Context =
      if (s == ":") standard else dir(Path.explode(s))
  }

  abstract class Context private {
    def enabled: Boolean
    def enabled(info: Sessions.Info): Boolean = enabled || info.browser_info
    def dir(store: Sessions.Store): Path = store.presentation_dir
  }


  /* maintain chapter index */

  private val sessions_path = Path.basic(".sessions")

  private def read_sessions(dir: Path): List[(String, String)] = {
    val path = dir + sessions_path
    if (path.is_file) {
      import XML.Decode._
      list(pair(string, string))(Symbol.decode_yxml(File.read(path)))
    }
    else Nil
  }

  def update_chapter(
    presentation_dir: Path,
    chapter: String,
    new_sessions: List[(String, String)]
  ): Unit = {
    val dir = Isabelle_System.make_directory(presentation_dir + Path.basic(chapter))

    val sessions0 =
      try { read_sessions(dir) }
      catch { case _: XML.Error => Nil }

    val sessions = (SortedMap.empty[String, String] ++ sessions0 ++ new_sessions).toList
    File.write(dir + sessions_path,
      {
        import XML.Encode._
        YXML.string_of_body(list(pair(string, string))(sessions))
      })

    val title = "Isabelle/" + chapter + " sessions"
    HTML.write_document(dir, "index.html",
      List(HTML.title(title + Isabelle_System.isabelle_heading())),
      HTML.chapter(title) ::
       (if (sessions.isEmpty) Nil
        else
          List(HTML.div("sessions",
            List(HTML.description(
              sessions.map({ case (name, description) =>
                val descr = Symbol.trim_blank_lines(description)
                (List(HTML.link(name + "/index.html", HTML.text(name))),
                  if (descr == "") Nil
                  else HTML.break ::: List(HTML.pre(HTML.text(descr)))) })))))),
      base = Some(presentation_dir))
  }

  def update_root(presentation_dir: Path): Unit = {
    Isabelle_System.make_directory(presentation_dir)
    HTML.init_fonts(presentation_dir)
    Isabelle_System.copy_file(Path.explode("~~/lib/logo/isabelle.gif"),
      presentation_dir + Path.explode("isabelle.gif"))
    val title = "The " + XML.text(Isabelle_System.isabelle_name()) + " Library"
    File.write(presentation_dir + Path.explode("index.html"),
      HTML.header +
"""
<head>
  """ + HTML.head_meta + """
  <title>""" + title + """</title>
</head>

<body text="#000000" bgcolor="#FFFFFF" link="#0000FF" vlink="#000099" alink="#404040">
  <center>
    <table width="100%" border="0" cellspacing="10" cellpadding="0">
      <tr>
        <td width="20%" valign="middle" align="center"><a href="https://isabelle.in.tum.de/"><img align="bottom" src="isabelle.gif" width="100" height="86" alt="[Isabelle]" border="0" /></a></td>

        <td width="80%" valign="middle" align="center">
          <table width="90%" border="0" cellspacing="0" cellpadding="20">
            <tr>
              <td valign="middle" align="center" bgcolor="#AACCCC"><font face="Helvetica,Arial" size="+2">""" + title + """</font></td>
            </tr>
          </table>
        </td>
      </tr>
    </table>
  </center>
  <hr />
""" + File.read(Path.explode("~~/lib/html/library_index_content.template")) +
"""
</body>
""" + HTML.footer)
  }


  /* present session */

  val session_graph_path: Path = Path.explode("session_graph.pdf")
  val readme_path: Path = Path.explode("README.html")

  def session_html(
    html_context: HTML_Context,
    session_context: Export.Session_Context,
    progress: Progress = new Progress,
    verbose: Boolean = false,
  ): Unit = {
    val session_name = session_context.session_name
    val session_info = session_context.sessions_structure(session_name)

    val session_dir =
      Isabelle_System.make_directory(html_context.session_dir(session_name)).expand

    progress.echo("Presenting " + session_name + " in " + session_dir + " ...")

    val session = html_context.nodes.the_session(session_name)

    Bytes.write(session_dir + session_graph_path,
      graphview.Graph_File.make_pdf(session_info.options,
        session_context.session_base.session_graph_display))

    val documents =
      for {
        doc <- session_info.document_variants
        db <- session_context.session_db()
        document <- Document_Build.read_document(db, session_name, doc.name)
      }
      yield {
        val doc_path = session_dir + doc.path.pdf
        if (verbose) progress.echo("Presenting document " + session_name + "/" + doc.name)
        if (session_info.document_echo) progress.echo("Document at " + doc_path)
        Bytes.write(doc_path, document.pdf)
        doc
      }

    val view_links = {
      val deps_link =
        HTML.link(session_graph_path, HTML.text("theory dependencies"))

      val readme_links =
        if ((session_info.dir + readme_path).is_file) {
          Isabelle_System.copy_file(session_info.dir + readme_path, session_dir + readme_path)
          List(HTML.link(readme_path, HTML.text("README")))
        }
        else Nil

      val document_links =
        documents.map(doc => HTML.link(doc.path.pdf, HTML.text(doc.name)))

      Library.separate(HTML.break ::: HTML.nl,
        (deps_link :: readme_links ::: document_links).
          map(link => HTML.text("View ") ::: List(link))).flatten
    }

    def present_theory(theory_name: String): Option[XML.Body] = {
      progress.expose_interrupt()

      for {
        command <- Build_Job.read_theory(session_context.theory(theory_name))
        theory <- html_context.theory_by_name(session_name, theory_name)
      }
      yield {
        if (verbose) progress.echo("Presenting theory " + quote(theory_name))
        val snapshot = Document.State.init.snippet(command)

        val thy_elements = theory.elements(html_context.elements)

        val thy_html =
          html_context.source(
            make_html(Entity_Context.make(html_context, session_name, theory_name, theory.thy_file),
              thy_elements, snapshot.xml_markup(elements = thy_elements.html)))

        val files_html =
          for {
            (src_path, xml) <- snapshot.xml_markup_blobs(elements = thy_elements.html)
            if xml.nonEmpty
          }
          yield {
            progress.expose_interrupt()
            if (verbose) progress.echo("Presenting file " + src_path)
            (src_path, html_context.source(make_html(Entity_Context.empty, thy_elements, xml)))
          }

        val files =
          for {
            (src_path, file_html) <- files_html
            file_path = html_context.files_path(session_name, src_path)
            rel_path <- File.relative_path(session_dir, file_path)
          }
          yield {
            val file_title = "File " + Symbol.cartouche_decoded(src_path.implode_short)
            HTML.write_document(file_path.dir, file_path.file_name,
              List(HTML.title(file_title)), List(html_context.head(file_title), file_html),
              base = Some(html_context.root_dir))
            List(HTML.link(rel_path.implode, HTML.text(file_title)))
          }

        val thy_title = "Theory " + theory.print_short

        HTML.write_document(session_dir, html_context.theory_html(theory).implode,
          List(HTML.title(thy_title)), List(html_context.head(thy_title), thy_html),
          base = Some(html_context.root_dir))

        List(HTML.link(html_context.theory_html(theory),
          HTML.text(theory.print_short) ::: (if (files.isEmpty) Nil else List(HTML.itemize(files)))))
      }
    }

    val theories = session.used_theories.flatMap(present_theory)

    val title = "Session " + session_name
      HTML.write_document(session_dir, "index.html",
        List(HTML.title(title + Isabelle_System.isabelle_heading())),
        html_context.head(title, List(HTML.par(view_links))) ::
          html_context.contents("Theories", theories),
        base = Some(html_context.root_dir))
  }
}
