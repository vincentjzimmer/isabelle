/*  Title:      Pure/Tools/build_stats.scala
    Author:     Makarius

Statistics from session build output.
*/

package isabelle


import scala.collection.mutable
import scala.util.matching.Regex


object Build_Stats
{
  /* parse build output */

  private val Session_Finished =
    new Regex("""^Finished (\S+) \((\d+):(\d+):(\d+) elapsed time, (\d+):(\d+):(\d+) cpu time.*$""")
  private val Session_Timing =
    new Regex("""^Timing (\S+) \((\d) threads, (\d+\.\d+)s elapsed time, (\d+\.\d+)s cpu time, (\d+\.\d+)s GC time.*$""")

  private object ML_Option
  {
    def unapply(s: String): Option[(String, String)] =
      s.indexOf('=') match {
        case -1 => None
        case i =>
          val a = s.substring(0, i)
          Library.try_unquote(s.substring(i + 1)) match {
            case Some(b) if Build.ml_options.contains(a) => Some((a, b))
            case _ => None
          }
      }
  }

  def parse(text: String): Build_Stats =
  {
    import Properties.Value

    val ml_options = new mutable.ListBuffer[(String, String)]
    var finished = Map.empty[String, Timing]
    var timing = Map.empty[String, Timing]
    var threads = Map.empty[String, Int]

    for (line <- split_lines(text)) {
      line match {
        case Session_Finished(name,
            Value.Int(e1), Value.Int(e2), Value.Int(e3),
            Value.Int(c1), Value.Int(c2), Value.Int(c3)) =>
          val elapsed = Time.hours_minutes_seconds(e1, e2, e3)
          val cpu = Time.hours_minutes_seconds(c1, c2, c3)
          finished += (name -> Timing(elapsed, cpu, Time.zero))
        case Session_Timing(name,
            Value.Int(t), Value.Double(e), Value.Double(c), Value.Double(g)) =>
          val elapsed = Time.seconds(e)
          val cpu = Time.seconds(c)
          val gc = Time.seconds(g)
          timing += (name -> Timing(elapsed, cpu, gc))
          threads += (name -> t)
        case ML_Option(option) => ml_options += option
        case _ =>
      }
    }

    Build_Stats(ml_options.toList, finished, timing, threads)
  }


  /* presentation */

  def present(job: String, history_length: Int, target_dir: Path)
  {
    val build_infos = CI_API.build_job_builds(job).sortBy(_.timestamp).reverse.take(history_length)
    if (build_infos.isEmpty) error("No build infos for job " + quote(job))

    val dir = target_dir + Path.basic(job)
    Isabelle_System.mkdirs(dir)

    val all_build_stats =
      Par_List.map((info: CI_API.Build_Info) =>
        (info.timestamp / 1000, parse(Url.read(info.output))), build_infos)
    val all_sessions =
      (Set.empty[String] /: all_build_stats)(
        { case (s, (_, stats)) => s ++ stats.sessions })

    for {
      session <- all_sessions
      if all_build_stats.filter({ case (_, stats) => stats.finished.isDefinedAt(session) }).length > 3
    } {
      Isabelle_System.with_tmp_file(session, "png") { data_file =>
        Isabelle_System.with_tmp_file(session, "gnuplot") { plot_file =>
          val data_file_name = File.standard_path(data_file.getAbsolutePath)
          val data =
            for { (t, stats) <- all_build_stats if stats.finished.isDefinedAt(session) }
            yield {
              val finished = stats.finished(session)
              t.toString + " " + finished.cpu.minutes + " " + finished.elapsed.minutes
            }
          File.write(data_file, cat_lines(data))

          File.write(plot_file, """
set terminal png size 1024,768
set output """ + quote(File.standard_path(dir + Path.basic(session + ".png"))) + """
set xdata time
set timefmt "%s"
set format x "%d-%b"
set xlabel """ + quote(session) + """
set key left top
plot [] [0:] """ +
  quote(data_file_name) + """ using 1:2 smooth sbezier title "interpolated cpu time",""" +
  quote(data_file_name) + """ using 1:2 smooth csplines title "cpu time", """ +
  quote(data_file_name) + """ using 1:3 smooth sbezier title "interpolated elapsed time",""" +
  quote(data_file_name) + """ using 1:3 smooth csplines title "elapsed time"
""")
          val result = Isabelle_System.bash("\"$ISABELLE_GNUPLOT\" " + File.bash_path(plot_file))
          if (result.rc != 0) {
            Output.error_message("Session " + session + ": gnuplot error")
            result.print
          }
        }
      }
    }
  }


  /* Isabelle tool wrapper */

  val isabelle_tool =
    Isabelle_Tool("build_stats", "present statistics from session build output", args =>
    {
      var target_dir = Path.explode("stats")
      var only_jobs = Set.empty[String]
      var history_length = 100

      val getopts = Getopts("""
Usage: isabelle build_stats [OPTIONS]

  Options are:
    -D DIR       target directory (default "stats")
    -J JOB       select named JOB (default: all jobs, multiple -J options possible)
    -l LENGTH    length of history (default 100)

  Present statistics from session build output, from Jenkins continuous build
  service specified as URL via ISABELLE_JENKINS_ROOT.
""",
        "D:" -> (arg => target_dir = Path.explode(arg)),
        "J:" -> (arg => only_jobs += arg),
        "l:" -> (arg => history_length = Properties.Value.Int.parse(arg)))

      val more_args = getopts(args)
      if (!more_args.isEmpty) getopts.usage()

      val all_jobs = CI_API.build_jobs()
      val jobs =
        if (only_jobs.isEmpty) all_jobs
        else {
          val bad = (only_jobs -- all_jobs).toList.sorted
          if (bad.isEmpty) only_jobs.toList
          else
            error("Unknown build jobs: " + commas_quote(bad) +
              "\nPossible jobs: " + commas_quote(all_jobs.sorted))
        }

      for (job <- jobs) {
        Output.writeln((target_dir + Path.basic(job)).implode)
        present(job, history_length, target_dir)
      }
    })
}

sealed case class Build_Stats(
  ml_options: List[(String, String)],
  finished: Map[String, Timing],
  timing: Map[String, Timing],
  threads: Map[String, Int])
{
  val sessions: Set[String] = finished.keySet ++ timing.keySet

  override def toString: String =
    sessions.toList.sorted.mkString("Build_Stats(", ", ", ")")
}
