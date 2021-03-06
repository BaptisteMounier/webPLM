package actors

import akka.actor._
import play.api.libs.json._
import play.api.mvc.RequestHeader
import json._
import models.PLM
import models.User
import log.PLMLogger
import spies._
import plm.core.model.lesson.Exercise
import plm.core.model.lesson.Lesson
import plm.core.model.lesson.Lecture
import plm.core.lang.ProgrammingLanguage
import plm.universe.Entity
import plm.universe.World
import plm.universe.IWorldView
import plm.universe.GridWorld
import plm.universe.GridWorldCell
import plm.universe.bugglequest.BuggleWorld
import plm.universe.bugglequest.AbstractBuggle
import plm.universe.bugglequest.BuggleWorldCell
import play.api.Play.current
import play.api.i18n.Lang
import play.api.Logger
import java.util.UUID
import models.daos.UserDAOMongoImpl

object PLMActor {
  def props(actorUUID: String, gitID: String, newUser: Boolean, preferredLang: Option[Lang], lastProgLang: Option[String])(out: ActorRef) = Props(new PLMActor(actorUUID, gitID, newUser, preferredLang, lastProgLang, out))
  def propsWithUser(actorUUID: String, user: User)(out: ActorRef) = Props(new PLMActor(actorUUID, user, out))
}

class PLMActor(actorUUID: String, gitID: String, newUser: Boolean, preferredLang: Option[Lang], lastProgLang: Option[String], out: ActorRef) extends Actor {  
  var availableLangs: Seq[Lang] = Lang.availables
  var plmLogger: PLMLogger = new PLMLogger(this)
  
  var resultSpy: ExecutionResultListener = null
  var progLangSpy: ProgLangListener  = null
  var humanLangSpy: HumanLangListener = null
  var registeredSpies: List[ExecutionSpy] = null
  
  var currentUser: User = null
  
  var currentPreferredLang: Lang = preferredLang.getOrElse(Lang("en"))
  
  var currentGitID: String = null
  setCurrentGitID(gitID, newUser)
  
  var plm: PLM = new PLM(currentGitID, plmLogger, currentPreferredLang.toLocale, lastProgLang)
  
  initSpies
  registerActor
  
  def this(actorUUID: String, user: User, out: ActorRef) {
    this(actorUUID, user.gitID.toString, false, user.preferredLang, user.lastProgLang, out)
    setCurrentUser(user)
  }
  
  def receive = {
    case msg: JsValue =>
      Logger.debug("Received a message")
      Logger.debug(msg.toString())
      var cmd: Option[String] = (msg \ "cmd").asOpt[String]
      cmd.getOrElse(None) match {
        case "signIn" | "signUp" =>
          setCurrentUser((msg \ "user").asOpt[User].get)
          registeredSpies.foreach { spy => spy.unregister }
          plm.setUserUUID(currentGitID)
          currentUser.preferredLang.getOrElse(None) match {
            case newLang: Lang =>
              currentPreferredLang = newLang
              plm.setLang(currentPreferredLang)
            case _ =>
              savePreferredLang()
          }
          plm.setProgrammingLanguage(currentUser.lastProgLang.getOrElse("Java"))
        case "signOut" =>
          clearCurrentUser()
          registeredSpies.foreach { spy => spy.unregister }
          plm.setUserUUID(currentGitID)
        case "getLessons" =>
          sendMessage("lessons", Json.obj(
            "lessons" -> LessonToJson.lessonsWrite(plm.lessons)
          ))
        case "setProgrammingLanguage" =>
          var optProgrammingLanguage: Option[String] = (msg \ "args" \ "programmingLanguage").asOpt[String]
          (optProgrammingLanguage.getOrElse(None)) match {
            case programmingLanguage: String =>
              plm.setProgrammingLanguage(programmingLanguage)
              saveLastProgLang(programmingLanguage)
            case _ =>
              Logger.debug("getExercise: non-correct JSON")
          }
        case "setLang" =>
          var optLang: Option[String] =  (msg \ "args" \ "lang").asOpt[String]
          (optLang.getOrElse(None)) match {
            case lang: String =>
              currentPreferredLang = Lang(lang)
              plm.setLang(currentPreferredLang)
              savePreferredLang()
            case _ =>
              Logger.debug("getExercise: non-correct JSON")
          }
        case "getExercise" =>
          var optLessonID: Option[String] = (msg \ "args" \ "lessonID").asOpt[String]
          var optExerciseID: Option[String] = (msg \ "args" \ "exerciseID").asOpt[String]
          var lecture: Lecture = null;
          var executionSpy: ExecutionSpy = new ExecutionSpy(this, "operations")
          var demoExecutionSpy: ExecutionSpy = new ExecutionSpy(this, "demoOperations")
          (optLessonID.getOrElse(None), optExerciseID.getOrElse(None)) match {
            case (lessonID:String, exerciseID: String) =>
              lecture = plm.switchExercise(lessonID, exerciseID, executionSpy, demoExecutionSpy)
            case (lessonID:String, _) =>
              lecture = plm.switchLesson(lessonID, executionSpy, demoExecutionSpy)
            case (_, _) =>
              Logger.debug("getExercise: non-correct JSON")
          }
          if(lecture != null) {
            sendMessage("exercise", Json.obj(
              "exercise" -> LectureToJson.lectureWrites(lecture, plm.programmingLanguage, plm.getStudentCode, plm.getInitialWorlds, plm.getSelectedWorldID)
            ))
          }
        case "runExercise" =>
          var optLessonID: Option[String] = (msg \ "args" \ "lessonID").asOpt[String]
          var optExerciseID: Option[String] = (msg \ "args" \ "exerciseID").asOpt[String]
          var optCode: Option[String] = (msg \ "args" \ "code").asOpt[String]
          (optLessonID.getOrElse(None), optExerciseID.getOrElse(None), optCode.getOrElse(None)) match {
            case (lessonID:String, exerciseID: String, code: String) =>
              plm.runExercise(lessonID, exerciseID, code)
            case (_, _, _) =>
              Logger.debug("runExercise: non-correctJSON")
          }
        case "runDemo" =>
          var optLessonID: Option[String] = (msg \ "args" \ "lessonID").asOpt[String]
          var optExerciseID: Option[String] = (msg \ "args" \ "exerciseID").asOpt[String]
          (optLessonID.getOrElse(None), optExerciseID.getOrElse(None)) match {
            case (lessonID:String, exerciseID: String) =>
              plm.runDemo(lessonID, exerciseID)
            case (_, _) =>
              Logger.debug("runDemo: non-correctJSON")
          }
        case "stopExecution" =>
          plm.stopExecution
        case "revertExercise" =>
          var lecture = plm.revertExercise
          sendMessage("exercise", Json.obj(
              "exercise" -> LectureToJson.lectureWrites(lecture, plm.programmingLanguage, plm.getStudentCode, plm.getInitialWorlds, plm.getSelectedWorldID)
          ))
        case "getExercises" =>
          if(plm.currentExercise != null) {
            var lectures = plm.game.getCurrentLesson.getRootLectures.toArray(Array[Lecture]())
            sendMessage("exercises", Json.obj(
              "exercises" -> ExerciseToJson.exercisesWrite(lectures) 
            ))
          }
        case "getLangs" =>
          sendMessage("langs", Json.obj(
            "selected" -> LangToJson.langWrite(currentPreferredLang),
            "availables" -> LangToJson.langsWrite(availableLangs)
          ))
        case _ =>
          Logger.debug("cmd: non-correct JSON")
      }
  }
  
  def createMessage(cmdName: String, mapArgs: JsValue): JsValue = {
    return Json.obj(
      "cmd" -> cmdName,
      "args" -> mapArgs
    )
  }
  
  def sendMessage(cmdName: String, mapArgs: JsValue) {
    out ! createMessage(cmdName, mapArgs)
  }
  
  def setCurrentUser(newUser: User) {
    currentUser = newUser
    sendMessage("user", Json.obj(
        "user" -> currentUser
      )
    )
    
    setCurrentGitID(currentUser.gitID.toString, false)
  }
  
  def clearCurrentUser() {
    currentUser = null
    sendMessage("user", Json.obj())
    
    currentGitID = UUID.randomUUID.toString
    setCurrentGitID(currentGitID, true)
  }
  
  def setCurrentGitID(newGitID: String, toSend: Boolean) {
    currentGitID = newGitID;
    if(toSend) {
      sendMessage("gitID", Json.obj(
          "gitID" -> currentGitID  
        )
      )
    }
  } 
  
  def initSpies() {
    resultSpy = new ExecutionResultListener(this, plm.game)
    plm.game.addGameStateListener(resultSpy)
    
    progLangSpy = new ProgLangListener(this, plm)
    plm.game.addProgLangListener(progLangSpy, true)
    
    humanLangSpy = new HumanLangListener(this, plm)
    plm.game.addHumanLangListener(humanLangSpy, true)
    
    registeredSpies = List()
  }
  
  def registerActor() {
    ActorsMap.add(actorUUID, self)
    sendMessage("actorUUID", Json.obj(
        "actorUUID" -> actorUUID  
      )
    )
  }
  
  def registerSpy(spy: ExecutionSpy) {
    registeredSpies = registeredSpies ::: List(spy)
  }
  
  def saveLastProgLang(programmingLanguage: String) {
    if(currentUser != null) {
      currentUser = currentUser.copy(
          lastProgLang = Some(programmingLanguage)
      )
      UserDAOMongoImpl.save(currentUser)
    }
  }
  
  def savePreferredLang() {
    if(currentUser != null) {
      currentUser = currentUser.copy(
          preferredLang = Some(currentPreferredLang)
      )
      UserDAOMongoImpl.save(currentUser)
    }
  }
  
  override def postStop() = {
    Logger.debug("postStop: websocket closed - removing the spies")
    ActorsMap.remove(actorUUID)
    plm.game.removeGameStateListener(resultSpy)
    plm.game.removeProgLangListener(progLangSpy)
    registeredSpies.foreach { spy => spy.unregister }
  }
}