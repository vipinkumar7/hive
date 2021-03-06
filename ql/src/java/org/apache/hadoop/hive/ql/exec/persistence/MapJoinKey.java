/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.persistence;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.common.type.Decimal128;
import org.apache.hadoop.hive.ql.debug.Utils;
import org.apache.hadoop.hive.ql.exec.ExprNodeEvaluator;
import org.apache.hadoop.hive.ql.exec.vector.VectorHashKeyWrapper;
import org.apache.hadoop.hive.ql.exec.vector.VectorHashKeyWrapperBatch;
import org.apache.hadoop.hive.ql.exec.vector.expressions.VectorExpressionWriter;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.serde2.ByteStream.Output;
import org.apache.hadoop.hive.serde2.SerDe;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.binarysortable.BinarySortableSerDe;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.hadoop.hive.serde2.lazybinary.LazyBinarySerDe;
import org.apache.hadoop.hive.serde2.lazybinary.LazyBinaryUtils;
import org.apache.hadoop.hive.serde2.lazybinary.LazyBinarySerDe.StringWrapper;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector.PrimitiveCategory;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;

import org.apache.hadoop.io.Writable;

/**
 * The base class for MapJoinKey.
 * Ideally, this should now be removed, some table wrappers have no key object.
 */
public abstract class MapJoinKey {
  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

  public abstract void write(MapJoinObjectSerDeContext context, ObjectOutputStream out)
      throws IOException, SerDeException;

  public abstract boolean hasAnyNulls(int fieldCount, boolean[] nullsafes);

  @SuppressWarnings("deprecation")
  public static MapJoinKey read(Output output, MapJoinKey key,
      MapJoinObjectSerDeContext context, Writable writable, boolean mayReuseKey)
          throws SerDeException {
    SerDe serde = context.getSerDe();
    Object obj = serde.deserialize(writable);
    boolean useOptimized = useOptimizedKeyBasedOnPrev(key);
    if (useOptimized || key == null) {
      byte[] structBytes = serialize(output, obj, serde.getObjectInspector(), !useOptimized);
      if (structBytes != null) {
        return MapJoinKeyBytes.fromBytes(key, mayReuseKey, structBytes);
      } else if (useOptimized) {
        throw new SerDeException(
            "Failed to serialize " + obj + " even though optimized keys are used");
      }
    }
    MapJoinKeyObject result = mayReuseKey ? (MapJoinKeyObject)key : new MapJoinKeyObject();
    result.read(serde.getObjectInspector(), obj);
    return result;
  }

  private static final HashSet<PrimitiveCategory> SUPPORTED_PRIMITIVES
      = new HashSet<PrimitiveCategory>();
  static {
    // All but decimal.
    SUPPORTED_PRIMITIVES.add(PrimitiveCategory.BOOLEAN);
    SUPPORTED_PRIMITIVES.add(PrimitiveCategory.VOID);
    SUPPORTED_PRIMITIVES.add(PrimitiveCategory.BOOLEAN);
    SUPPORTED_PRIMITIVES.add(PrimitiveCategory.BYTE);
    SUPPORTED_PRIMITIVES.add(PrimitiveCategory.SHORT);
    SUPPORTED_PRIMITIVES.add(PrimitiveCategory.INT);
    SUPPORTED_PRIMITIVES.add(PrimitiveCategory.LONG);
    SUPPORTED_PRIMITIVES.add(PrimitiveCategory.FLOAT);
    SUPPORTED_PRIMITIVES.add(PrimitiveCategory.DOUBLE);
    SUPPORTED_PRIMITIVES.add(PrimitiveCategory.STRING);
    SUPPORTED_PRIMITIVES.add(PrimitiveCategory.DATE);
    SUPPORTED_PRIMITIVES.add(PrimitiveCategory.TIMESTAMP);
    SUPPORTED_PRIMITIVES.add(PrimitiveCategory.BINARY);
    SUPPORTED_PRIMITIVES.add(PrimitiveCategory.VARCHAR);
    SUPPORTED_PRIMITIVES.add(PrimitiveCategory.CHAR);
  }

  private static byte[] serialize(Output byteStream,
      Object obj, ObjectInspector oi, boolean checkTypes) throws SerDeException {
    if (null == obj || !(oi instanceof StructObjectInspector)) {
      return null; // not supported
    }
    StructObjectInspector soi = (StructObjectInspector)oi;
    List<? extends StructField> fields = soi.getAllStructFieldRefs();
    int size = fields.size();
    if (size > 8) {
      return null; // not supported
    } else if (size == 0) {
      return EMPTY_BYTE_ARRAY; // shortcut for null keys
    }
    Object[] fieldData = new Object[size];
    List<ObjectInspector> fieldOis = new ArrayList<ObjectInspector>(size);
    for (int i = 0; i < size; ++i) {
      StructField field = fields.get(i);
      ObjectInspector foi = field.getFieldObjectInspector();
      if (checkTypes && !isSupportedField(foi)) {
        return null;
      }
      fieldData[i] = soi.getStructFieldData(obj, field);
      fieldOis.add(foi);
    }

    byteStream = serializeRow(byteStream, fieldData, fieldOis, null);
    return Arrays.copyOf(byteStream.getData(), byteStream.getLength());
  }

  public static boolean isSupportedField(ObjectInspector foi) {
    if (foi.getCategory() != Category.PRIMITIVE) return false; // not supported
    PrimitiveCategory pc = ((PrimitiveObjectInspector)foi).getPrimitiveCategory();
    if (!SUPPORTED_PRIMITIVES.contains(pc)) return false; // not supported
    return true;
  }

  public static MapJoinKey readFromVector(Output output, MapJoinKey key, VectorHashKeyWrapper kw,
      VectorExpressionWriter[] keyOutputWriters, VectorHashKeyWrapperBatch keyWrapperBatch,
      boolean mayReuseKey) throws HiveException {
    boolean useOptimized = useOptimizedKeyBasedOnPrev(key);
    if (useOptimized || key == null) {
      byte[] structBytes = null;
      try {
        if (keyOutputWriters.length <= 8) {
          output = serializeVector(output, kw, keyOutputWriters, keyWrapperBatch, null, null);
          structBytes = Arrays.copyOf(output.getData(), output.getLength());
        }
      } catch (SerDeException e) {
        throw new HiveException(e);
      }
      if (structBytes != null) {
        return MapJoinKeyBytes.fromBytes(key, mayReuseKey, structBytes);
      } else if (useOptimized) {
        throw new HiveException(
            "Failed to serialize " + kw + " even though optimized keys are used");
      }
    }
    MapJoinKeyObject result = mayReuseKey ? (MapJoinKeyObject)key : new MapJoinKeyObject();
    result.readFromVector(kw, keyOutputWriters, keyWrapperBatch);
    return result;
  }

  /**
   * Serializes row to output for vectorized path.
   * @param byteStream Output to reuse. Can be null, in that case a new one would be created.
   */
  public static Output serializeVector(Output byteStream, VectorHashKeyWrapper kw,
      VectorExpressionWriter[] keyOutputWriters, VectorHashKeyWrapperBatch keyWrapperBatch,
      boolean[] nulls, boolean[] sortableSortOrders) throws HiveException, SerDeException {
    Object[] fieldData = new Object[keyOutputWriters.length];
    List<ObjectInspector> fieldOis = new ArrayList<ObjectInspector>();
    for (int i = 0; i < keyOutputWriters.length; ++i) {
      VectorExpressionWriter writer = keyOutputWriters[i];
      fieldOis.add(writer.getObjectInspector());
      // This is rather convoluted... to simplify for perf, we could call getRawKeyValue
      // instead of writable, and serialize based on Java type as opposed to OI.
      fieldData[i] = keyWrapperBatch.getWritableKeyValue(kw, i, writer);
      if (nulls != null) {
        nulls[i] = (fieldData[i] == null);
      }
    }
    return serializeRow(byteStream, fieldData, fieldOis, sortableSortOrders);
  }

  public static MapJoinKey readFromRow(Output output, MapJoinKey key, Object row,
      List<ExprNodeEvaluator> fields, List<ObjectInspector> keyFieldsOI, boolean mayReuseKey)
          throws HiveException {
    Object[] fieldObjs = new Object[fields.size()];
    for (int keyIndex = 0; keyIndex < fields.size(); ++keyIndex) {
      fieldObjs[keyIndex] = fields.get(keyIndex).evaluate(row);
    }
    boolean useOptimized = useOptimizedKeyBasedOnPrev(key);
    if (useOptimized || key == null) {
      try {
        byte[] structBytes = null;
        if (fieldObjs.length <= 8) {
          if (fieldObjs.length == 0) {
            structBytes = EMPTY_BYTE_ARRAY; // shortcut for null keys
          } else {
            output = serializeRow(output, fieldObjs, keyFieldsOI, null);
            structBytes = Arrays.copyOf(output.getData(), output.getLength());
          }
        }
        if (structBytes != null) {
          return MapJoinKeyBytes.fromBytes(key, mayReuseKey, structBytes);
        } else if (useOptimized) {
          throw new HiveException(
              "Failed to serialize " + row + " even though optimized keys are used");
        }
      } catch (SerDeException ex) {
        throw new HiveException("Serialization error", ex);
      }
    }
    MapJoinKeyObject result = mayReuseKey ? (MapJoinKeyObject)key : new MapJoinKeyObject();
    result.readFromRow(fieldObjs, keyFieldsOI);
    return result;
  }

  private static final Log LOG = LogFactory.getLog(MapJoinKey.class);


  /**
   * Serializes row to output.
   * @param byteStream Output to reuse. Can be null, in that case a new one would be created.
   */
  public static Output serializeRow(Output byteStream, Object[] fieldData,
      List<ObjectInspector> fieldOis, boolean[] sortableSortOrders) throws SerDeException {
    if (byteStream == null) {
      byteStream = new Output();
    } else {
      byteStream.reset();
    }
    if (fieldData.length == 0) {
      byteStream.reset();
    } else if (sortableSortOrders == null) {
      LazyBinarySerDe.serializeStruct(byteStream, fieldData, fieldOis);
    } else {
      BinarySortableSerDe.serializeStruct(byteStream, fieldData, fieldOis, sortableSortOrders);
    }
    return byteStream;
  }

  private static boolean useOptimizedKeyBasedOnPrev(MapJoinKey key) {
    return (key != null) && (key instanceof MapJoinKeyBytes);
  }
}
