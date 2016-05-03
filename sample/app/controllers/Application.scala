package controllers

import javax.inject._
import play.api._
import play.api.mvc._
import models._
import se.radley.plugin.salat._
import com.mongodb.casbah.Imports._
import com.novus.salat._

@Singleton
class Application @Inject() extends Controller {

  def list() = Action {
    val users = User.findAll
    Ok(views.html.list(users))
  }

  def view(id: ObjectId) = Action {
    User.findOneById(id).map( user =>
      Ok(views.html.user(user))
    ).getOrElse(NotFound)
  }
}
