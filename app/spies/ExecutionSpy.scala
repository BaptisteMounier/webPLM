package spies

import play.api.libs.json._

import plm.universe.World
import plm.universe.IWorldView

import plm.universe.Operation
import plm.universe.Entity

import actors.PLMActor
import play.api.Logger
import json.operation.OperationToJson

class ExecutionSpy(plmActor: PLMActor, messageID: String) extends IWorldView {  
  var world: World = _
    
  def getPLMActor = plmActor
  def getMessageID = messageID
 
  override def clone(): ExecutionSpy = {
    return new ExecutionSpy(plmActor, messageID)
  }
  
  def setWorld(w: World) {
    world = w
    world.addWorldUpdatesListener(this)
    plmActor.registerSpy(this)
  }
  
  def unregister() {
    world.removeWorldUpdatesListener(this)
  }
  
  /**
   * Called every time something changes: entity move, new entity, entity gets destroyed, etc.
   */
  def worldHasMoved() {
    world.getEntities.toArray(Array[Entity]()).foreach { entity => 
      if(entity.isReadyToSend) {
        Logger.debug("The world moved!")
        
        var mapArgs: JsValue = Json.obj(
          "worldID" -> world.getName,
          "operations" -> OperationToJson.operationsWrite(entity.getOperations.toArray(Array[Operation]()))
        )
        entity.getOperations.clear
        entity.setReadyToSend(false)
        plmActor.sendMessage(messageID, mapArgs)
    
      }
    }
  }
  
  /**
   * Called when entities are created or destroyed, not when they move
   */
  def worldHasChanged() {
    // Do not care?
  }
  
  override def equals(o: Any) = {
    o match {
      case spy: ExecutionSpy => 
        spy.getPLMActor == this.plmActor && spy.getMessageID == this.messageID
      case _ => false
    }
  }
}