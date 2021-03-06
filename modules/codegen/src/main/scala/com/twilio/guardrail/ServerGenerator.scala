package com.twilio.guardrail

import cats.instances.all._
import cats.syntax.all._
import com.twilio.guardrail.generators.LanguageParameter
import com.twilio.guardrail.languages.LA
import com.twilio.guardrail.protocol.terms.Responses
import com.twilio.guardrail.protocol.terms.server.{ GenerateRouteMeta, ServerTerms }
import com.twilio.guardrail.terms.framework.FrameworkTerms
import com.twilio.guardrail.terms.{ LanguageTerms, RouteMeta, SecurityScheme, SwaggerTerms }

case class Servers[L <: LA](servers: List[Server[L]], supportDefinitions: List[SupportDefinition[L]])
case class Server[L <: LA](pkg: List[String], extraImports: List[L#Import], handlerDefinition: L#Definition, serverDefinitions: List[L#Definition])
case class TracingField[L <: LA](param: LanguageParameter[L], term: L#Term)
case class RenderedRoutes[L <: LA](
    routes: List[L#Statement],
    classAnnotations: List[L#Annotation],
    methodSigs: List[L#MethodDeclaration],
    supportDefinitions: List[L#Definition],
    handlerDefinitions: List[L#Statement]
)

object ServerGenerator {
  def fromSwagger[L <: LA, F[_]](context: Context, supportPackage: List[String], basePath: Option[String], frameworkImports: List[L#Import])(
      groupedRoutes: List[(List[String], List[RouteMeta])]
  )(
      protocolElems: List[StrictProtocolElems[L]],
      securitySchemes: Map[String, SecurityScheme[L]]
  )(implicit Fw: FrameworkTerms[L, F], Sc: LanguageTerms[L, F], S: ServerTerms[L, F], Sw: SwaggerTerms[L, F]): F[Servers[L]] = {
    import S._
    import Sw._
    import Sc._

    for {
      extraImports       <- getExtraImports(context.tracing, supportPackage)
      supportDefinitions <- generateSupportDefinitions(context.tracing, securitySchemes)
      servers <- groupedRoutes.traverse {
        case (className, unsortedRoutes) =>
          val routes = unsortedRoutes.sortBy(r => (r.path.unwrapTracker, r.method))
          for {
            resourceName <- formatTypeName(className.lastOption.getOrElse(""), Some("Resource"))
            handlerName  <- formatTypeName(className.lastOption.getOrElse(""), Some("Handler"))
            responseServerPair <- routes.traverse {
              case route @ RouteMeta(path, method, operation, securityRequirements) =>
                for {
                  operationId         <- getOperationId(operation)
                  responses           <- Responses.getResponses(operationId, operation, protocolElems)
                  responseClsName     <- formatTypeName(operationId, Some("Response"))
                  responseDefinitions <- generateResponseDefinitions(responseClsName, responses, protocolElems)
                  methodName          <- formatMethodName(operationId)
                  parameters          <- route.getParameters[L, F](protocolElems)
                  tracingField        <- buildTracingFields(operation, className, context.tracing)
                } yield (responseDefinitions, GenerateRouteMeta(operationId, methodName, responseClsName, tracingField, route, parameters, responses))
            }
            (responseDefinitions, serverOperations) = responseServerPair.unzip
            renderedRoutes   <- generateRoutes(context.tracing, resourceName, handlerName, basePath, serverOperations, protocolElems, securitySchemes)
            handlerSrc       <- renderHandler(handlerName, renderedRoutes.methodSigs, renderedRoutes.handlerDefinitions, responseDefinitions.flatten)
            extraRouteParams <- getExtraRouteParams(context.tracing)
            classSrc <- renderClass(
              resourceName,
              handlerName,
              renderedRoutes.classAnnotations,
              renderedRoutes.routes,
              extraRouteParams,
              responseDefinitions.flatten,
              renderedRoutes.supportDefinitions
            )
          } yield {
            Server(className, frameworkImports ++ extraImports, handlerSrc, classSrc)
          }
      }
    } yield Servers[L](servers, supportDefinitions)
  }
}
