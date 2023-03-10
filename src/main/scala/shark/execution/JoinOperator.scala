package shark.execution

import java.util.{HashMap => JHashMap, List => JList}

import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.ql.exec.{JoinOperator => HiveJoinOperator}
import org.apache.hadoop.hive.ql.plan.{JoinDesc, TableDesc}
import org.apache.hadoop.hive.serde2.{Deserializer, Serializer, SerDeUtils}
import org.apache.hadoop.hive.serde2.objectinspector.StandardStructObjectInspector
import org.apache.hadoop.io.BytesWritable

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._
import scala.reflect.BeanProperty

import spark.{CoGroupedRDD, HashPartitioner, RDD}
import spark.SparkContext._


class JoinOperator extends CommonJoinOperator[JoinDesc, HiveJoinOperator]
  with HiveTopOperator {

  @BeanProperty var valueTableDescMap: JHashMap[Int, TableDesc] = _
  @BeanProperty var keyTableDesc: TableDesc = _

  @transient var tagToValueSer: JHashMap[Int, Deserializer] = _
  @transient var keyDeserializer: Deserializer = _
  @transient var keyObjectInspector: StandardStructObjectInspector = _

  override def initializeOnMaster() {
    super.initializeOnMaster()
    valueTableDescMap = new JHashMap[Int, TableDesc]
    valueTableDescMap ++= keyValueTableDescs.map { case(tag, kvdescs) => (tag, kvdescs._2) }
    keyTableDesc = keyValueTableDescs.head._2._1

    // Call initializeOnSlave to initialize the join filters, etc.
    initializeOnSlave()
  }

  override def initializeOnSlave() {
    super.initializeOnSlave()

    tagToValueSer = new JHashMap[Int, Deserializer]
    valueTableDescMap foreach { case(tag, tableDesc) =>
      logDebug("tableDescs (tag %d): %s".format(tag, tableDesc))

      val deserializer = tableDesc.getDeserializerClass.newInstance().asInstanceOf[Deserializer]
      deserializer.initialize(null, tableDesc.getProperties())

      logDebug("value deser (tag %d): %s".format(tag, deserializer))
      tagToValueSer.put(tag, deserializer)
    }

    if (nullCheck) {
      keyDeserializer = keyTableDesc.getDeserializerClass.newInstance.asInstanceOf[Deserializer]
      keyDeserializer.initialize(null, keyTableDesc.getProperties())
      keyObjectInspector =
        keyDeserializer.getObjectInspector().asInstanceOf[StandardStructObjectInspector]
    }
  }

  override def execute(): RDD[_] = {
    val inputRdds = executeParents()
    combineMultipleRdds(inputRdds)
  }

  override def combineMultipleRdds(rdds: Seq[(Int, RDD[_])]): RDD[_] = {
    // Determine the number of reduce tasks to run.
    var numReduceTasks = hconf.getIntVar(HiveConf.ConfVars.HADOOPNUMREDUCERS)
    if (numReduceTasks < 1) {
      numReduceTasks = 1
    }

    // Turn the RDD into a map. Use a Java HashMap to avoid Scala's annoying
    // Some/Option. Add an assert for sanity check. If ReduceSink's join tags
    // are wrong, the hash entries might collide.
    val rddsJavaMap = new JHashMap[Int, RDD[_]]
    rddsJavaMap ++= rdds
    assert(rdds.size == rddsJavaMap.size, {
      logError("rdds.size (%d) != rddsJavaMap.size (%d)".format(rdds.size, rddsJavaMap.size))
    })

    val rddsInJoinOrder = order.map { inputIndex =>
      rddsJavaMap.get(inputIndex.byteValue.toInt).asInstanceOf[RDD[(ReduceKey, Any)]]
    }

    val part = new HashPartitioner(numReduceTasks)
    val cogrouped = new CoGroupedRDD[ReduceKey](
      rddsInJoinOrder.toSeq.asInstanceOf[Seq[RDD[(_, _)]]], part)

    val op = OperatorSerializationWrapper(this)

    cogrouped.mapPartitions { part =>
      op.initializeOnSlave()

      val tmp = new Array[Object](2)
      val writable = new BytesWritable
      val nullSafes = op.conf.getNullSafes()

      val cp = new CartesianProduct[Any](op.numTables)

      part.flatMap { case (k: ReduceKey, bufs: Array[_]) =>
        writable.set(k.bytes)

        // If nullCheck is false, we can skip deserializing the key.
        if (op.nullCheck &&
            SerDeUtils.hasAnyNullObject(
              op.keyDeserializer.deserialize(writable).asInstanceOf[JList[_]],
              op.keyObjectInspector,
              nullSafes)) {
          bufs.zipWithIndex.flatMap { case (buf, label) =>
            val bufsNull = Array.fill(op.numTables)(ArrayBuffer[Any]())
            bufsNull(label) = buf
            op.generateTuples(cp.product(bufsNull.asInstanceOf[Array[Seq[Any]]], op.joinConditions))
          }
        } else {
          op.generateTuples(cp.product(bufs.asInstanceOf[Array[Seq[Any]]], op.joinConditions))
        }
      }
    }
  }

  def generateTuples(iter: Iterator[Array[Any]]): Iterator[_] = {

    val tupleOrder = CommonJoinOperator.computeTupleOrder(joinConditions)

    val bytes = new BytesWritable()
    val tmp = new Array[Object](2)

    val tupleSizes = (0 until joinVals.size).map { i => joinVals.get(i.toByte).size() }.toIndexedSeq
    val offsets = tupleSizes.scanLeft(0)(_ + _)

    val rowSize = offsets.last
    val outputRow = new Array[Object](rowSize)

    iter.map { elements: Array[Any] =>
      var index = 0
      while (index < numTables) {
        val element = elements(index).asInstanceOf[Array[Byte]]
        var i = 0
        if (element == null) {
          while (i < joinVals.get(index.toByte).size) {
            outputRow(i + offsets(index)) = null
            i += 1
          }
        } else {
          bytes.set(element, 0, element.length)
          tmp(1) = tagToValueSer.get(index).deserialize(bytes)
          val joinVal = joinVals.get(index.toByte)
          while (i < joinVal.size) {
            outputRow(i + offsets(index)) = joinVal(i).evaluate(tmp)
            i += 1
          }
        }
        index += 1
      }

      outputRow
    }
  }

  override def processPartition[T](iter: Iterator[T]): Iterator[_] =
    throw new UnsupportedOperationException("JoinOperator.processPartition()")
}
