/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2017 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */


package edp.rider.rest.persistence.dal

import edp.rider.common.RiderLogger
import edp.rider.module.DbModule._
import edp.rider.rest.persistence.base.BaseDalImpl
import edp.rider.rest.persistence.entities._
import edp.rider.rest.router.ActionClass
import edp.rider.rest.util.{CommonUtils, StreamUtils}
import edp.rider.rest.util.CommonUtils._
import slick.jdbc.MySQLProfile.api._
import slick.lifted.{CanBeQueryCondition, TableQuery}
import edp.rider.rest.util.FlowUtils._
import edp.rider.service.util.CacheMap

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}

class FlowDal(flowTable: TableQuery[FlowTable], streamTable: TableQuery[StreamTable], projectTable: TableQuery[ProjectTable], streamDal: StreamDal, inTopicDal: StreamInTopicDal)
  extends BaseDalImpl[FlowTable, Flow](flowTable) with RiderLogger {

  def defaultGetAll[C: CanBeQueryCondition](f: (FlowTable) => C, action: String = "refresh"): Future[Seq[FlowStream]] = {
    val flows = Await.result(super.findByFilter(f), minTimeOut)

    val streamIds = flows.map(_.streamId).distinct
    val streamMap = Await.result(streamDal.findByFilter(_.id inSet (streamIds)), minTimeOut)
      .map(stream => (stream.id, StreamInfo(stream.name, stream.streamType, stream.functionType, stream.status)))
      .toMap[Long, StreamInfo]

    val flowStreams = flows.map(flow => FlowStream(flow.id, flow.projectId, flow.streamId, flow.sourceNs, flow.sinkNs, flow.consumedProtocol,
      flow.sinkConfig, flow.tranConfig, flow.status, flow.startedTime, flow.stoppedTime, flow.active, flow.createTime,
      flow.createBy, flow.updateTime, flow.updateBy, streamMap(flow.streamId).name, streamMap(flow.streamId).status, streamMap(flow.streamId).streamType, streamMap(flow.streamId).functionType, "", ""))

    val flowDisableActions = getDisableActions(flowStreams)

    Future(flowStreams.map(flowStream => {
      newFlowStream(FlowStream(flowStream.id, flowStream.projectId, flowStream.streamId, flowStream.sourceNs, flowStream.sinkNs, flowStream.consumedProtocol,
        flowStream.sinkConfig, flowStream.tranConfig, flowStream.status, flowStream.startedTime, flowStream.stoppedTime, flowStream.active, flowStream.createTime,
        flowStream.createBy, flowStream.updateTime, flowStream.updateBy, flowStream.streamName, flowStream.streamStatus, flowStream.streamType, flowStream.functionType, flowDisableActions(flowStream.id), flowStream.msg), action)
    }))

    //    val flowStreams = db.run((flowTable.withFilter(f) join streamTable on (_.streamId === _.id))
    //        .map {
    //          case (flow, stream) => (flow.id, flow.projectId, flow.streamId, flow.sourceNs, flow.sinkNs, flow.consumedProtocol,
    //            flow.sinkConfig, flow.tranConfig, flow.status, flow.startedTime, flow.stoppedTime, flow.active, flow.createTime, flow.createBy, flow.updateTime, flow.updateBy,
    //            stream.name, stream.status, stream.streamType, "", "") <> (FlowStream.tupled, FlowStream.unapply)
    //        }.result).mapTo[Seq[FlowStream]]
    //        flowStreams.map[Seq[FlowStream]] {
    //          flowStreams =>
    //            val map = getDisableActions(flowStreams)
    //            flowStreams.map(flowStream => {
    //              newFlowStream(FlowStream(flowStream.id, flowStream.projectId, flowStream.streamId, flowStream.sourceNs, flowStream.sinkNs, flowStream.consumedProtocol,
    //                flowStream.sinkConfig, flowStream.tranConfig, flowStream.status, flowStream.startedTime, flowStream.stoppedTime, flowStream.active, flowStream.createTime,
    //                flowStream.createBy, flowStream.updateTime, flowStream.updateBy, flowStream.streamName, flowStream.streamStatus, flowStream.streamType, map(flowStream.id), flowStream.msg), action)
    //            })
    //    }
  }


  def getById(projectId: Long, flowId: Long): Future[Option[FlowStreamInfo]] = {
    try {
      val flowStreamOpt = Await.result(defaultGetAll(_.id === flowId), minTimeOut).headOption
      flowStreamOpt match {
        case Some(flowStream) =>
          val stream = streamDal.getBriefDetail(Some(projectId), Some(Seq(flowStream.streamId))).head
          val map = getDisableActions(Seq(flowStream))
          Future(Some(FlowStreamInfo(flowStream.id, flowStream.projectId, flowStream.streamId, flowStream.sourceNs, flowStream.sinkNs, flowStream.consumedProtocol,
            flowStream.sinkConfig, flowStream.tranConfig, flowStream.status, flowStream.startedTime, flowStream.stoppedTime, flowStream.active, flowStream.createTime, flowStream.createBy, flowStream.updateTime,
            flowStream.updateBy, flowStream.streamName, flowStream.streamStatus, flowStream.streamType, flowStream.functionType, map(flowStream.id), stream.kafkaInfo.instance, "")))
        case None => Future(None)
      }
    } catch {
      case ex: Exception =>
        riderLogger.error(s"Failed to get flow $flowId", ex)
        throw ex
    }

  }

  def adminGetById(projectId: Long, flowId: Long): Future[Option[FlowStreamAdmin]] = {
    try {
      val flowStreamOpt = Await.result(defaultGetAll(_.id === flowId), minTimeOut).headOption
      flowStreamOpt match {
        case Some(flowStream) =>
          val stream = streamDal.getStreamDetail(Some(projectId), Some(Seq(flowStream.streamId))).head
          Future(Some(FlowStreamAdmin(flowStream.id, flowStream.projectId, stream.projectName, flowStream.streamId, flowStream.sourceNs, flowStream.sinkNs, flowStream.consumedProtocol,
            flowStream.sinkConfig, flowStream.tranConfig, flowStream.startedTime, flowStream.stoppedTime, flowStream.status, flowStream.active, flowStream.createTime, flowStream.createBy, flowStream.updateTime,
            flowStream.updateBy, flowStream.streamName, flowStream.streamStatus, flowStream.streamType, flowStream.functionType, flowStream.disableActions, flowStream.msg)))
        case None => Future(None)
      }
    } catch {
      case ex: Exception =>
        riderLogger.error(s"Failed to get flow $flowId", ex)
        throw ex
    }
  }

  def adminGetAll(visible: Boolean = true): Future[Seq[FlowStreamAdmin]] = {
    try {
      defaultGetAll(_.active === true).map[Seq[FlowStreamAdmin]] {
        flowStreams =>
          flowStreams.map {
            flowStream =>
              val project = Await.result(db.run(projectTable.filter(_.id === flowStream.projectId).result).mapTo[Seq[Project]], maxTimeOut).head
              //              val returnStartedTime = if (flowStream.startedTime.getOrElse("") == "") Some("") else flowStream.startedTime
              //              val returnStoppedTime = if (flowStream.stoppedTime.getOrElse("") == "") Some("") else flowStream.stoppedTime
              FlowStreamAdmin(flowStream.id, flowStream.projectId, project.name, flowStream.streamId, flowStream.sourceNs, flowStream.sinkNs, flowStream.consumedProtocol,
                flowStream.sinkConfig, flowStream.tranConfig, flowStream.startedTime, flowStream.stoppedTime, flowStream.status, flowStream.active, flowStream.createTime, flowStream.createBy, flowStream.updateTime,
                flowStream.updateBy, flowStream.streamName, flowStream.streamStatus, flowStream.streamType, flowStream.functionType, flowStream.disableActions, flowStream.msg)
          }
      }
    } catch {
      case ex: Exception =>
        riderLogger.error(s"Failed to get all flows", ex)
        throw ex
    }

  }

  def updateStatusByFeedback(flowId: Long, flowNewStatus: String) = {
    if (flowNewStatus == "failed")
      Await.result(db.run(flowTable.filter(flow => flow.id === flowId && flow.status =!= "failed").map(c => (c.status, c.stoppedTime, c.updateTime)).update(flowNewStatus, Some(currentSec), currentSec)), minTimeOut)
    else if (flowNewStatus == "stopped")
      Await.result(db.run(flowTable.filter(flow => flow.id === flowId && flow.status =!= "stopped").map(c => (c.status, c.stoppedTime, c.updateTime)).update(flowNewStatus, Some(currentSec), currentSec)), minTimeOut)
    else if (flowNewStatus == "running")
      Await.result(db.run(flowTable.filter(flow => flow.id === flowId && flow.status =!= "running").map(c => (c.status, c.startedTime, c.updateTime)).update(flowNewStatus, Some(currentSec), currentSec)), minTimeOut)
  }

  def updateStatusByAction(flowId: Long, flowNewStatus: String, startTime: Option[String], stopTime: Option[String]) = {
    Await.result(db.run(flowTable.filter(_.id === flowId).map(c => (c.status, c.startedTime, c.stoppedTime)).update(flowNewStatus, startTime, stopTime)), minTimeOut)
  }

  def getByNs(projectId: Long, sourceNs: String, sinkNs: String): Flow = {
    Await.result(db.run(flowTable.filter(_.active === true).filter(_.projectId === projectId).filter(_.sourceNs === sourceNs).filter(_.sinkNs === sinkNs).result).mapTo[Seq[Flow]], minTimeOut).head
  }

  def getByNsOnly(sourceNs: String, sinkNs: String) = {
    Await.result(db.run(flowTable.filter(_.active === true).filter(_.sourceNs === sourceNs).filter(_.sinkNs === sinkNs).result).mapTo[Seq[Flow]], minTimeOut)
  }

  def newFlowStream(flowStream: FlowStream, action: String): FlowStream = {
    try {
      val flowStatus = actionRule(flowStream, action)
      val startedTime = if (action == "start" || action == "renew") Some(currentSec) else flowStream.startedTime
      val stoppedTime = if (action == "stop") Some(currentSec)
      else if (action == "start" || action == "renew") null
      else flowStream.stoppedTime

      updateStatusByAction(flowStream.id, flowStatus.flowStatus, startedTime, stoppedTime)

      val flow = FlowStream(flowStream.id, flowStream.projectId, flowStream.streamId, flowStream.sourceNs, flowStream.sinkNs, flowStream.consumedProtocol,
        flowStream.sinkConfig, flowStream.tranConfig, flowStatus.flowStatus, startedTime, stoppedTime, flowStream.active, flowStream.createTime, flowStream.createBy, flowStream.updateTime,
        flowStream.updateBy, flowStream.streamName, flowStream.streamStatus, flowStream.streamType, flowStream.functionType, flowStatus.disableActions, flowStatus.msg)
      flow
    } catch {
      case ex: Exception =>
        riderLogger.error(s"update flow status by actionRule failed", ex)
        throw ex
    }
  }

  def getAllActiveFlowName: Future[Seq[FlowCacheMap]] =
    db.run(flowTable.map(flow => (flow.sourceNs, flow.sinkNs, flow.id)).result).map[Seq[FlowCacheMap]] {
      flows =>
        flows.map(flow => FlowCacheMap(flow._1 + "_" + flow._2, flow._3))
    }


  def flowAction(flowAction: ActionClass, userId: Long): Future[Seq[FlowStream]] = {
    try {
      val flowIdSeq = flowAction.flowIds.split(",").map(_.toLong)
      val flowSeq = Await.result(super.findByFilter(_.id inSet flowIdSeq), minTimeOut)
      if (flowAction.action == "delete") {
        deleteFlow(flowSeq, userId)
      } else {
        updateTimeAndUser(flowIdSeq, userId)
        val flowStreamSeq = defaultGetAll(_.id inSet flowIdSeq)
        flowStreamSeq.map[Seq[FlowStream]] {
          flowStreamSeq => {
            streamDal.refreshStreamStatus(None, Some(flowStreamSeq.map(_.streamId).distinct))
            flowStreamSeq.map {
              flowStream => newFlowStream(flowStream, flowAction.action)
            }
          }
        }
      }
    } catch {
      case ex: Exception =>
        riderLogger.error(s"user $userId ${
          flowAction.action
        } flow ${
          flowAction.flowIds
        } failed", ex)
        throw ex
    }
  }

  def insertOrAbort(flows: Seq[Flow]): Seq[Flow] = {
    try {
      val flowInsertSeq = new ListBuffer[Flow]
      flows.foreach(
        flow => {
          val search = Await.result(super.findByFilter(row => row.sourceNs === flow.sourceNs && row.sinkNs === flow.sinkNs), minTimeOut)
          if (search.isEmpty)
            flowInsertSeq += Await.result(insert(flow), CommonUtils.minTimeOut)
        }
      )
      flowInsertSeq
    } catch {
      case ex: Exception =>
        riderLogger.error(s"flow insert or abort failed", ex)
        throw ex
    }
  }

  def deleteFlow(flowSeq: Seq[Flow], userId: Long) = {
    val flowStream = Await.result(defaultGetAll(_.id inSet flowSeq.map(_.id)), minTimeOut)
    flowStream.foreach(flow => {
      stopFlow(flow.streamId, flow.id, userId, flow.streamType, flow.sourceNs, flow.sinkNs, flow.tranConfig.getOrElse(""))
      Await.result(super.deleteById(flow.id), minTimeOut)
      CacheMap.flowCacheMapRefresh
    })
    riderLogger.info(s"user $userId delete flow ${
      flowSeq.map(_.id).mkString(",")
    } success")
    Future(Seq())
  }

  def updateTimeAndUser(flowIds: Seq[Long], userId: Long) = {
    Await.result(db.run(flowTable.filter(_.id inSet flowIds).map(c => (c.updateTime, c.updateBy)).update(currentSec, userId)), minTimeOut)
  }

  def getActiveStatusIdsByStreamId(streamId: Long): Seq[Long] = {
    Await.result(db.run(flowTable.filter(flow => flow.streamId === streamId && flow.status =!= "new" && flow.status =!= "stopping" && flow.status =!= "stopped").map(_.id).result), minTimeOut)
  }
}
