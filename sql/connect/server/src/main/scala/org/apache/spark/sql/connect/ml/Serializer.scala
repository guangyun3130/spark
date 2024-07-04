/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.connect.ml

import scala.jdk.CollectionConverters.CollectionHasAsScala

import org.apache.spark.connect.proto
import org.apache.spark.ml.linalg.{DenseVector, Matrix, SparseVector, Vector, Vectors}
import org.apache.spark.ml.param.Params
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.connect.common.LiteralValueProtoConverter
import org.apache.spark.sql.connect.service.SessionHolder

object Serializer {

  def serialize(data: Any, objIdentifier: String): proto.MlCommandResponse = {
    data match {
      case v: Vector => serializeVector(v)
      case v: Matrix => serializeMatrix(v)
      case _: Byte | _: Short | _: Int | _: Long | _: Float | _: Double | _: Boolean | _: String |
          _: Array[_] =>
        proto.MlCommandResponse
          .newBuilder()
          .setLiteral(LiteralValueProtoConverter.toLiteralProto(data))
          .build()
      case _ => // if didn't match, we just return the method chain to client
        proto.MlCommandResponse
          .newBuilder()
          .setModelAttribute(objIdentifier)
          .build()
    }
  }

  private def serializeVector(data: Vector): proto.MlCommandResponse = {
    // TODO: Support sparse
    val values = data.toArray
    val denseBuilder = proto.Vector.Dense.newBuilder()
    for (i <- 0 until values.length) {
      denseBuilder.addValue(values(i))
    }
    proto.MlCommandResponse
      .newBuilder()
      .setVector(proto.Vector.newBuilder().setDense(denseBuilder))
      .build()
  }

  private def serializeMatrix(data: Matrix): proto.MlCommandResponse = {
    // TODO: Support sparse
    // TODO: optimize transposed case
    val denseBuilder = proto.Matrix.Dense.newBuilder()
    val values = data.toArray
    for (i <- 0 until values.length) {
      denseBuilder.addValue(values(i))
    }
    denseBuilder.setNumCols(data.numCols)
    denseBuilder.setNumRows(data.numRows)
    denseBuilder.setIsTransposed(false)
    proto.MlCommandResponse
      .newBuilder()
      .setMatrix(proto.Matrix.newBuilder().setDense(denseBuilder))
      .build()
  }

  def deserializeMethodArguments(
      args: Array[proto.FetchModelAttr.Args],
      sessionHolder: SessionHolder): Array[(Object, Class[_])] = {
    args.map { arg =>
      if (arg.hasLiteral) {
        arg.getLiteral.getLiteralTypeCase match {
          case proto.Expression.Literal.LiteralTypeCase.INTEGER =>
            (arg.getLiteral.getInteger.asInstanceOf[Object], classOf[Int])
          case proto.Expression.Literal.LiteralTypeCase.FLOAT =>
            (arg.getLiteral.getFloat.toDouble.asInstanceOf[Object], classOf[Double])
          case proto.Expression.Literal.LiteralTypeCase.STRING =>
            (arg.getLiteral.getString, classOf[String])
          case proto.Expression.Literal.LiteralTypeCase.DOUBLE =>
            (arg.getLiteral.getDouble.asInstanceOf[Object], classOf[Double])
          case proto.Expression.Literal.LiteralTypeCase.BOOLEAN =>
            (arg.getLiteral.getBoolean.asInstanceOf[Object], classOf[Boolean])
          case _ =>
            throw new UnsupportedOperationException(arg.getLiteral.getLiteralTypeCase.name())

        }
      } else if (arg.hasVector) {
        if (arg.getVector.hasDense) {
          val values = arg.getVector.getDense.getValueList.asScala.map(_.toDouble).toArray
          (Vectors.dense(values), classOf[DenseVector])
        } else {
          val size = arg.getVector.getSparse.getSize
          val indices = arg.getVector.getSparse.getIndexList.asScala.map(_.toInt).toArray
          val values = arg.getVector.getSparse.getValueList.asScala.map(_.toDouble).toArray
          (Vectors.sparse(size, indices, values), classOf[SparseVector])
        }
      } else if (arg.hasInput) {
        (MLUtils.parseRelationProto(arg.getInput, sessionHolder), classOf[Dataset[_]])
      } else {
        throw new UnsupportedOperationException("deserializeMethodArguments")
      }
    }
  }

  def serializeParams(instance: Params): proto.MlParams = {
    val builder = proto.MlParams.newBuilder()
    instance.params.foreach { param =>
      if (instance.isSet(param)) {
        val v = LiteralValueProtoConverter.toLiteralProto(instance.get(param).get)
        builder.putParams(param.name, v)
      }
    }
    builder.build()
  }
}
