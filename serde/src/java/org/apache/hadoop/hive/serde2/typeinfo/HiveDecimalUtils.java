package org.apache.hadoop.hive.serde2.typeinfo;

import java.math.BigDecimal;

import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;

public class HiveDecimalUtils {

  public static HiveDecimal enforcePrecisionScale(HiveDecimal dec, DecimalTypeInfo typeInfo) {
    return enforcePrecisionScale(dec, typeInfo.precision(), typeInfo.scale());
  }

  public static HiveDecimal enforcePrecisionScale(HiveDecimal dec,int maxPrecision, int maxScale) {
    if (dec == null) {
      return null;
    }

    // Minor optimization, avoiding creating new objects.
    if (dec.precision() - dec.scale() <= maxPrecision - maxScale && dec.scale() <= maxScale) {
      return dec;
    }

    BigDecimal bd = HiveDecimal.enforcePrecisionScale(dec.bigDecimalValue(),
        maxPrecision, maxScale);
    if (bd == null) {
      return null;
    }

    return HiveDecimal.create(bd);
  }

  public static HiveDecimalWritable enforcePrecisionScale(HiveDecimalWritable writable,
      DecimalTypeInfo typeInfo) {
    if (writable == null) {
      return null;
    }

    HiveDecimal dec = enforcePrecisionScale(writable.getHiveDecimal(), typeInfo);
    return dec == null ? null : new HiveDecimalWritable(dec);
  }

  public static HiveDecimalWritable enforcePrecisionScale(HiveDecimalWritable writable,
      int precision, int scale) {
    if (writable == null) {
      return null;
    }

    HiveDecimal dec = enforcePrecisionScale(writable.getHiveDecimal(), precision, scale);
    return dec == null ? null : new HiveDecimalWritable(dec);
  }

  public static void validateParameter(int precision, int scale) {
    if (precision < 1 || precision > HiveDecimal.MAX_PRECISION) {
      throw new IllegalArgumentException("Decimal precision out of allowed range [1," +
          HiveDecimal.MAX_PRECISION + "]");
    }

    if (scale < 0 || scale > HiveDecimal.MAX_SCALE) {
      throw new IllegalArgumentException("Decimal scale out of allowed range [0," +
          HiveDecimal.MAX_SCALE + "]");
    }

    if (precision < scale) {
      throw new IllegalArgumentException("Decimal scale must be less than or equal to precision");
    }
  }

  /**
   * Get the precision of double type can be tricky. While a double may have more digits than
   * a HiveDecimal can hold, in reality those numbers are of no practical use. Thus, we assume
   * that a double can have at most HiveDecimal.MAX_PRECISION, which is generous enough. This
   * implies that casting a double to a decimal type is always valid.
   *
   */
  public static int getPrecisionForType(PrimitiveTypeInfo typeInfo) {
    switch (typeInfo.getPrimitiveCategory()) {
    case DECIMAL:
      return ((DecimalTypeInfo)typeInfo).precision();
    case FLOAT:
      return 23;
    case BYTE:
      return 3;
    case SHORT:
      return 5;
    case INT:
      return 10;
    case LONG:
      return 19;
    default:
      return HiveDecimal.MAX_PRECISION;
    }
  }

  /**
   * Get the scale of double type can be tricky. While a double may have more decimal digits than
   * HiveDecimal, in reality those numbers are of no practical use. Thus, we assume that a double
   * can have at most HiveDecimal.MAX_SCALE, which is generous enough. This implies implies that
   * casting a double to a decimal type is always valid.
   *
   */
  public static int getScaleForType(PrimitiveTypeInfo typeInfo) {
    switch (typeInfo.getPrimitiveCategory()) {
    case DECIMAL:
      return ((DecimalTypeInfo)typeInfo).scale();
    case FLOAT:
      return 7;
    case BYTE:
      return 0;
    case SHORT:
      return 0;
    case INT:
      return 0;
    case LONG:
      return 0;
    default:
      return HiveDecimal.MAX_SCALE;
    }
  }

}
