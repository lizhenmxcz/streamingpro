package streaming.dsl.mmlib.algs

import org.apache.spark.graphx.lib.PageRank
import org.apache.spark.graphx.{Edge, Graph, VertexId}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.types.{DoubleType, LongType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SparkSession}
import streaming.dsl.mmlib.SQLAlg

/**
  * Created by allwefantasy on 13/1/2018.
  */
class SQLPageRank extends SQLAlg with Functions {
  override def train(df: DataFrame, path: String, params: Map[String, String]): Unit = {
    val vertexCol = params.getOrElse("vertexCol", "vertextIds").toString
    val edgeSrcCol = params.getOrElse("edgeSrcCol", "edgeSrc").toString
    val edgeDstCol = params.getOrElse("edgeDstCol", "edgeDst").toString

    val edges: RDD[Edge[Any]] = df.rdd.map { f =>
      Edge(f.getAs[Long](edgeSrcCol), f.getAs[Long](edgeDstCol))
    }

    var vertex: RDD[(VertexId, Any)] = null
    if (params.contains("vertexCol")) {
      vertex = df.rdd.map { f =>
        (f.getAs[Long](vertexCol), "")
      }
    } else {
      vertex = edges.flatMap(f => List(f.srcId, f.dstId)).distinct().map { f =>
        (f, "")
      }
    }

    val graph = Graph(vertex, edges, ())
    val tol = params.getOrElse("tol", "0.001").toDouble
    val resetProb = params.getOrElse("resetProb", "0.15").toDouble
    val pr = PageRank.runUntilConvergence(graph, tol, resetProb)
    val verticesDf = df.sparkSession.createDataFrame(pr.vertices.map(f => Row(f._1, f._2)),
      StructType(Seq(StructField("vertextId", LongType), StructField("pagerank", DoubleType))))

    val edgesDf = df.sparkSession.createDataFrame(pr.edges.map(f => Row(f.srcId, f.dstId, f.attr)),
      StructType(Seq(StructField("srcId", LongType), StructField("dstId", LongType), StructField("weight", DoubleType))))

    verticesDf.write.mode(SaveMode.Overwrite).parquet(path + "/vertices")
    edgesDf.write.mode(SaveMode.Overwrite).parquet(path + "/edges")

  }

  override def load(sparkSession: SparkSession, path: String): Any = {
    import sparkSession._
    val verticesDf = sparkSession.read.parquet(path + "/vertices")
    verticesDf.collect().map(f => (f.getLong(0), f.getDouble(1))).toMap
  }

  override def predict(sparkSession: SparkSession, _model: Any, name: String): UserDefinedFunction = {
    val model = sparkSession.sparkContext.broadcast(_model.asInstanceOf[Map[Long, Double]])

    val f = (vertexId: Long) => {
      model.value(vertexId)
    }
    UserDefinedFunction(f, DoubleType, Some(Seq(LongType)))
  }
}
