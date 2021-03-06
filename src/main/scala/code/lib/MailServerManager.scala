package code.lib

import scala.collection.mutable.HashMap

import org.subethamail.smtp.server.SMTPServer
import org.subethamail.smtp.MessageHandlerFactory
import org.subethamail.smtp.MessageHandler
import org.subethamail.smtp.RejectException
import org.subethamail.smtp.MessageContext

import com.sun.mail.smtp.SMTPMessage
import javax.mail.{Part, Multipart}

import net.liftweb.common.Logger
import net.liftweb.actor._
import code.comet.{MailStats, NewMessage}

import code.model.Message
import code.model.Recipient
import code.model.MessageRecipient

import java.io.{IOException, InputStream, ByteArrayInputStream}
import java.util.Date

import org.apache.commons.io.IOUtils

object MailServerManager extends Logger{
  var serverMap = new HashMap[String, SMTPServer]
 
  def startServer(name: String, port: Int) {
    info("Starting mail server "+name+" on port "+port.toString)
    var server = new SMTPServer(new LoggingMessageHandlerFactory())
    server.setPort(port)
    serverMap += name -> server
    server.start()
  }

  def stopServer(name: String) = {
    info("Shutting down mailserver "+name)
    serverMap.remove(name) match {
      case None => false
      case Some(x) => {x.stop() ; true}
    }
  }

  def portsInUse():Iterable[Int] = 
    serverMap.values.map{i => i.getPort}
}

class LoggingMessageHandlerFactory extends MessageHandlerFactory {
  def create(ctx:MessageContext) = new Handler(ctx)
}

class Handler(ctx: MessageContext) extends MessageHandler with Logger {
  var from = ""
  var recipients = List[String]()
  var msg : SMTPMessage = _

  debug("Creating new Handler")

  @throws(classOf[RejectException])
  def from(f: String) {
    from = f
  }

  @throws(classOf[RejectException])
  def recipient(recipient: String) {
    recipients = recipient :: recipients
  }

  @throws(classOf[IOException])
  def data(data: InputStream) {
    val base = IOUtils.toByteArray(data)
    msg = new SMTPMessage(null, new ByteArrayInputStream(base))
    val msgatt = new MimeAttachments(msg)
    info("Message received.")
    debug("All recipients: " ++ (recipients mkString " "))

    val msg_entity = Message.create
    msg_entity sender from
    msg_entity subject msg.getSubject()
    msg_entity sentDate (new Date())
    msg_entity messageId msg.getMessageID()
    msg_entity msgBody base
    msg_entity textContent (msgatt.getText getOrElse null)
    msg_entity save
    var recipient_entities = recipients.map({x:String => Recipient.recipientFindOrNew(x)})
    recipient_entities.map({x:Recipient => x save; MessageRecipient.join(x,msg_entity)})
    MailStats ! NewMessage(msg_entity)
    MessageIndex ! NewMessage(msg_entity)
  }

  def done {}
}


