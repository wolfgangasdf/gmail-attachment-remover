/*
    This file is part of SGAR - Scala Gmail attachment Remover.

    SGAR is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    SGAR is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Foobar.  If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * This file contains all the network communication with Gmail.
 *
 * All public useful methods have their own exception handling, i.e., they can be called in another thread (e.g., future).
 *
 */

package sgar

import org.json.simple.{JSONObject, JSONValue}

import scalaj.http.Http

import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks._
import scala.io.BufferedSource

import java.io.{PrintWriter, FileInputStream, FileOutputStream}
import java.util.Properties
import java.net.{ServerSocket, URLDecoder, URLEncoder}
import javax.mail._
import javax.mail.internet._
import com.sun.mail.gimap._
import com.sun.mail.iap.Argument
import com.sun.mail.imap.{IMAPMessage, IMAPFolder}
import com.sun.mail.imap.IMAPFolder.ProtocolCommand
import com.sun.mail.imap.protocol.IMAPProtocol


object GmailStuff {
  var backupdir: java.io.File = _
  var username = ""
  var gmailsearch = ""
  var minbytes = 0
  var limit = 0
  var label = ""
  var refreshtoken = ""

  var store: Store = _
  var session: Session = _

  // OAuth2
  val clientid = "217209351804-pf92dc077qrvtotiro7b9lcl6pjrhfhq.apps.googleusercontent.com"
  val clientsecret = "WqZuf6h_xD0al4vH5jolJyds"

  def heads(s: String, len: Int): String = if (s != null) s.substring(0, math.min(len, s.length)) else ""

  class Bodypart(val bpi: Int, val filename: String, val filesize: Int, val contentType: String)

  class ToDelete(val gmid: Long,
                 val bodyparts: ListBuffer[Bodypart],
                 val from: String,
                 val subject: String,
                 val date: String) {
    override def toString: String = gmid.toString + ": " + heads(subject, 10) + "; bps: " + bodyparts.map(bp => bp.bpi).mkString(",")
  }

  def flagToString(f: Flags.Flag): String = {
    f match {
      case Flags.Flag.ANSWERED => "answered"
      case Flags.Flag.DELETED => "deleted"
      case Flags.Flag.DRAFT => "draft"
      case Flags.Flag.FLAGGED => "flagged"
      case Flags.Flag.RECENT => "recent"
      case Flags.Flag.SEEN => "seen"
      case Flags.Flag.USER => "user"
    }
  }

  private def connect() {
    println("connecting...")
    println(" getting oauth2 access token...")
    if (refreshtoken == null) throw new Exception("refresh token error, please re-authenticate!")
    val oauth2_access_token = OAuth2google.RefreshTokens(clientid, clientsecret, refreshtoken)
    println(" connecting to gmail...")

    val props = new Properties()
    props.put("mail.store.protocol", "gimaps")
    props.put("mail.gimaps.sasl.enable", "true")
    props.put("mail.gimaps.sasl.mechanisms", "XOAUTH2")
    props.put("mail.imaps.sasl.mechanisms.oauth2.oauthToken", oauth2_access_token)
    session = Session.getInstance(props)

    store = session.getStore("gimaps")
    store.connect("imap.gmail.com", username, oauth2_access_token)
    println("connected: " + store.isConnected)
  }

  def getAllFolders: ListBuffer[String] = {
    val lb = new ListBuffer[String]()
    try {
      println("Get all gmail folders...")
      connect()
      def listFolderRec(folder: Folder, level: Int): Unit = {
        val s = folder.getFullName
        println(("." * level) + s)
        lb += s
        val childs = folder.list
        for (ch <- childs) listFolderRec(ch, level + 1)
      }
      listFolderRec(store.getDefaultFolder, 0)
      store.close()
      println("Get all gmail folders finished!")
    } catch {
      case e: Exception => e.printStackTrace()
    } finally {
      store.close()
    }
    lb
  }

  def getToDelete: ListBuffer[ToDelete] = {
    val dellist = new ListBuffer[ToDelete]()
    try {
      println("Find requested emails with attachments...")
      connect()
      val inbox = store.getFolder("[Gmail]/All Mail")
      inbox.open(Folder.READ_WRITE)
      val msgs = inbox.search(new GmailRawSearchTerm(gmailsearch + (if (label.isEmpty) "" else " label:" + label)))
      println(s" ${msgs.length} emails found matching the criteria, querying the first limit=$limit emails only!")
      var count = 0
      breakable {
        for (message <- msgs) {
          var dodelete = false
          val bps = new ListBuffer[Bodypart]()
          val gm = message.asInstanceOf[GmailMessage]
          println(s"checking gid=${gm.getMsgId} subj=${message.getSubject} labels:${gm.getLabels.mkString(",")}")
          val mp = gm.getContent
          mp match {
            case mmpm: MimeMultipart =>
              for (i <- 0 until mmpm.getCount) {
                val bp = mmpm.getBodyPart(i)
                if (bp.getSize > minbytes && bp.getFileName != null) {
                  bps += new Bodypart(i, bp.getFileName, bp.getSize, bp.getContentType)
                  dodelete = true
                }
              }
            case _ => println("  unknown mp.class = " + mp.getClass)
          }
          if (dodelete) {
            dellist += new ToDelete(gm.getMsgId, bps, gm.getFrom.head.toString, gm.getSubject, gm.getSentDate.toString)
            count += 1
          }
          if (count > limit) break()
        }
      }
      inbox.close(true)
      println("Found all requested emails!")
    } catch {
      case e: Exception => e.printStackTrace()
    } finally {
      store.close()
    }
    dellist
  }

  def doDelete(dellist: ListBuffer[ToDelete]) {
    try {
      var startns: Long = -1
      var allmail: IMAPFolder = null
      var trash: IMAPFolder = null
      println("deleting attachments of " + dellist.length + " emails...")
      var count = 0
      for (todel <- dellist) {
        count += 1
        if (startns == -1 || (System.nanoTime - startns)/1e9 > 10 * 60) {
          // re-open connection incl auth after 10 minutes... Gmail seems to drop it from time to time.
          println("----- Re-connect after 10 minutes...")
          if (store.isConnected) store.close()
          connect()
          allmail = store.getFolder("[Gmail]/All Mail").asInstanceOf[IMAPFolder]
          trash = store.getFolder("[Gmail]/Trash").asInstanceOf[IMAPFolder]
          allmail.open(Folder.READ_WRITE)
          startns = System.nanoTime
          println("----- Re-connected!")
        }
        println(s"[$count/${dellist.length}] gmid=${todel.gmid} subj=${todel.subject} date=${todel.date}")

        val msg = allmail.search(new GmailMsgIdTerm(todel.gmid)).head

        // backup labels & flags (do before any msg download which sets SEEN flag!)
        val oldlabels = msg.asInstanceOf[GmailMessage].getLabels.toBuffer[String]
        val oldflags = msg.getFlags

        println("backup message...")
        val backupfile = new java.io.File(backupdir, todel.gmid.toString + ".msg")
        println("  backupfile=" + backupfile.getPath)
        val parent = backupfile.getParentFile
        if (parent != null)
          if (!parent.exists())
            parent.mkdirs()
        val os = new FileOutputStream(backupfile)
        msg.writeTo(os)
        os.close()

        println("re-creating message...")
        val is = new FileInputStream(backupfile)
        val newmsg = new MimeMessage(session, is)
        is.close()

        println("remove attachments...")
        val mmpm = newmsg.getContent.asInstanceOf[MimeMultipart]
        // do in reverse order!
        for (bpdel <- todel.bodyparts.reverse) mmpm.removeBodyPart(bpdel.bpi)
        // re-add as empty attachment
        for (bpdel <- todel.bodyparts) {
          val nbp = new MimeBodyPart()
          nbp.setHeader("Content-Type", "text/plain")
          nbp.setFileName(bpdel.filename + ".txt")
          nbp.setText("Removed attachment <" + bpdel.filename + "> of size <" + bpdel.filesize + ">")
          mmpm.addBodyPart(nbp)
        }

        // save changes
        newmsg.saveChanges()

        println("putting message back into gmail...")
        val resm = allmail.addMessages(Array(newmsg)).head
        val newgmailid = resm.asInstanceOf[GmailMessage].getMsgId
        println(" newmsg gmail id=" + newgmailid)

        println("deleting message in gmail and emptying trash (can take long)...")
        allmail.copyMessages(Array(msg), trash)
        trash.open(Folder.READ_WRITE)
        val tmsgs = trash.getMessages
        for (tmsg <- tmsgs) tmsg.setFlag(Flags.Flag.DELETED, true)
        trash.close(true)

        println("re-open folder...")
        // re-open folder, this is essential for doCommand() to work below!
        allmail.close(true)
        allmail.open(Folder.READ_WRITE)

        val nmsgx = allmail.search(new GmailMsgIdTerm(newgmailid)).head
        val nmsg = nmsgx.asInstanceOf[IMAPMessage]

        // restore flags like unread
        println("old system flags=[" + oldflags.getSystemFlags.mkString(";") + "][" + oldflags.getSystemFlags.map(f => flagToString(f)).mkString(";") + "] user flags=" + oldflags.getUserFlags.mkString(";"))
        allmail.setFlags(Array(nmsgx), oldflags, true) // 2016: this seems to also restore labels/folders!

        // TODO: this doesn't work: all flags gone... because of threaded view/all emails in thread have tag?
        // should work: http://stackoverflow.com/questions/17263500/how-to-remove-a-label-from-an-email-message-from-gmail-by-using-the-imap-protoco
        if (label != "") {
          println(s"Remove label [$label]...")
          allmail.doCommand(new ProtocolCommand {
            override def doCommand(protocol: IMAPProtocol): AnyRef = {
              val args = new Argument()
              args.writeString("" + nmsg.getMessageNumber)
              args.writeString("-X-GM-LABELS")
              args.writeString(label)
              val r = protocol.command("STORE", args)
              if (!r.last.isOK) {
                println(s"ERROR removing label/folder [$label]!")
                println("ERROR: args:\n ")
                println(args)
                println("ERROR: responses:\n ")
                for (rr <- r) println(rr)
                throw new MessagingException("error setting labels of email!")
              }
              null
            }
          })
        }


        /* labels are now in systemflags??? I have to restore the systemflags because of unread etc.
        if (label != "") { // remove tag label!
          oldlabels -= label
        }
        if (oldlabels.isEmpty) {
          println("no labels to restore!")
        } else {
          println("Restoring labels (without tag-label): " + oldlabels.mkString(";"))
          allmail.doCommand(new ProtocolCommand {
            override def doCommand(protocol: IMAPProtocol): AnyRef = {
              val args = new Argument()
              args.writeString("" + nmsg.getMessageNumber)
              args.writeString("+X-GM-LABELS")
              for (lab <- oldlabels) args.writeString(lab)
              val r = protocol.command("STORE", args)
              if (!r.last.isOK) {
                println("ERROR: oldlabel=" + oldlabels)
                println("ERROR: args:\n ")
                println(args)
                println("ERROR: responses:\n ")
                for (rr <- r) println(rr)
                throw new MessagingException("error setting labels of email!")
              }
              null
            }
          })
        }
        */
      }
      allmail.close(true)
      println("Removing attachments finished!")
    } catch {
      case e: Exception => e.printStackTrace()
    } finally {
      store.close()
    }
  }

/*
  def doTest() {
    try {
      connect()
      val inbox = store.getFolder("[Gmail]/All Mail").asInstanceOf[IMAPFolder]
      inbox.open(Folder.READ_WRITE)

      println("gmailsearch=" + gmailsearch)
      val msgs = inbox.search(new GmailMsgIdTerm("1488957505766432509".toLong))
      for (m <- msgs) println("msg: " + m.getSubject + m.getSentDate)
      val nmsg = msgs.head.asInstanceOf[IMAPMessage]
      println("nmsg: " + nmsg.getMessageID + " num=" + nmsg.getMessageNumber + " gid=" + nmsg.asInstanceOf[GmailMessage].getMsgId)

      val flags = nmsg.getFlags
      println("sflags = " + flags.getSystemFlags.map(f => flagToString(f)).mkString(";"))
      println("uflags = " + flags.getUserFlags.mkString(";"))

      inbox.close(true)
      store.close()
      println("finished!")
    } catch {
      case e: Exception => e.printStackTrace()
    } finally {
      println("store close")
      store.close()
    }
  }
*/

  def cleanup(): Unit = {
    if (store != null) if (store.isConnected) store.close()
  }
}

object OAuth2google {

  val GOOGLE_ACCOUNTS_BASE_URL = "https://accounts.google.com"

  def AccountsUrl(command: String) = s"$GOOGLE_ACCOUNTS_BASE_URL/$command"

  def UrlEscape(text: String): String = URLEncoder.encode(text, "UTF-8")

  def UrlUnescape(text: String): String = URLDecoder.decode(text, "UTF-8")

  def FormatUrlParams(params: Seq[(String, String)]): String = {
    params map { case (k, v) => s"$k=${UrlEscape(v)}" } mkString "&"
  }
  def getRedirectURI(localWebserver: Boolean): String = if (localWebserver) "http://localhost:9631" else "urn:ietf:wg:oauth:2.0:oob"
  // generate URL that should be opened in browser, optional for use with localWebserver
  def GeneratePermissionUrl(client_id: String, localWebserver: Boolean): String = {
    val params = Seq(
      ("client_id", client_id),
      ("redirect_uri", getRedirectURI(localWebserver)),
      ("scope", "https://mail.google.com/"),
      ("response_type", "code"))
    s"${AccountsUrl("o/oauth2/auth")}?${FormatUrlParams(params)}"
  }

  def GenURL(base: String, params: Seq[(String, String)]): String = {
    s"$base?${FormatUrlParams(params)}"
  }

  // option I: use built-in webserver to catch response (localWebserver=true above)
  def catchResponse(): String = {
    println("Waiting for redirect from Google...")
    val port = 9631
    val listener = new ServerSocket(port)
    val sock = listener.accept()
    val rec = new BufferedSource(sock.getInputStream).getLines()
    val s = rec.next()
    new PrintWriter(sock.getOutputStream, true).println("Gmail attachment remover has received the reply. Please close this window!")
    sock.close()
    val re = """GET\ \/\?code=(.*)\ HTTP\/.*""".r
    s match {
      case re(code) => code
      case _ => null
    }
  }

  // option II: user had to enter code manually (localWebserver=false above)
  def AuthorizeTokens(client_id: String, client_secret: String, authorization_code: String, localWebserver: Boolean): (String, String) = {
    val params = Seq(
      ("client_id", client_id),
      ("client_secret", client_secret),
      ("code", authorization_code),
      ("redirect_uri", getRedirectURI(localWebserver)),
      ("grant_type", "authorization_code"))
    val response = Http(AccountsUrl("o/oauth2/token")).postForm(params).asString
    if (!response.isSuccess) throw new Exception("error retrieving auth token response = " + response)
    val r1 = JSONValue.parse(response.body).asInstanceOf[JSONObject]
    (r1.get("access_token").toString, r1.get("refresh_token").toString)
  }

  // call this all the time after initial auth!
  def RefreshTokens(client_id: String, client_secret: String, refresh_token: String): String = {
    val params = Seq(
      ("client_id", client_id),
      ("client_secret", client_secret),
      ("refresh_token", refresh_token),
      ("grant_type", "refresh_token"))
    val response = Http(AccountsUrl("o/oauth2/token")).postForm(params).asString
    if (!response.isSuccess) throw new Exception("error retrieving refresh token response = " + response)
    val r1 = JSONValue.parse(response.body).asInstanceOf[JSONObject]
    r1.get("access_token").toString
  }

}