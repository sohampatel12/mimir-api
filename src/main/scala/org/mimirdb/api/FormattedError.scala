package org.mimirdb.api

case class FormattedError(response: ErrorResponse) extends Exception(response.errorMessage)

object FormattedError
{
  def apply(
    e: Throwable, 
    msg: String, 
    className: String = "org.mimirdb.api.FormattedError"
  ): FormattedError = 
    FormattedError(ErrorResponse(e, msg, className))

}