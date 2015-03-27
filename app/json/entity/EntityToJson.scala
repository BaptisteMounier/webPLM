package json.entity

import play.api.libs.json._
import plm.universe.Entity
import plm.universe.bugglequest.AbstractBuggle
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import plm.universe.turtles.Turtle

object EntityToJson {
  
  def entitiesWrite(entities: Array[Entity]): JsValue = {
    var json: JsValue = Json.obj()
    entities.foreach { entity => 
      json = json.as[JsObject] ++ entityWrite(entity).as[JsObject] 
    }
    return json
  }
  
  def entityWrite(entity: Entity): JsValue = {
    var json: JsValue = null
    entity match {
      case abstractBuggle: AbstractBuggle =>
        json = AbstractBuggleToJson.abstractBuggleWrite(abstractBuggle)
      case turtle: Turtle =>
        json = TurtleToJson.turtleWrite(turtle)
    }
    
    return Json.obj( 
        entity.getName -> json
    )
  }
}