package common.io.Encoding.encoder.regular;

import common.io.Encoding.decoder.Decoder;
import common.io.Encoding.decoder.FloatDecoder;
import common.io.Encoding.encoder.Encoder;
import common.io.Encoding.encoder.FloatEncoder;
import common.io.Enums.DataType;
import common.io.Enums.Encoding;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class FloatDecoderTest {
    private static final Logger logger = LoggerFactory.getLogger(FloatDecoderTest.class);
    private final double delta = 0.0000001;
    private final int floatMaxPointValue = 10000;
    private final int floatMaxPointNumber = 4;
    private final long doubleMaxPointValue = 1000000000000000L;
    private final int doubleMaxPointNumber = 15;
    private List<Float> floatList;
    private List<Double> doubleList;

    @Before
    public void setUp() {
        floatList = new ArrayList<Float>();
        int hybridCount = 11;
        int hybridNum = 5;
        int hybridStart = 20;
        for (int i = 0; i < hybridNum; i++) {
            for (int j = 0; j < hybridCount; j++) {
                floatList.add((float) hybridStart / floatMaxPointValue);
                hybridStart += 3;
            }
            for (int j = 0; j < hybridCount; j++) {
                floatList.add((float) hybridStart / floatMaxPointValue);
            }
            hybridCount += 2;
        }

        doubleList = new ArrayList<Double>();
        int hybridCountDouble = 11;
        int hybridNumDouble = 5;
        long hybridStartDouble = 20;

        for (int i = 0; i < hybridNumDouble; i++) {
            for (int j = 0; j < hybridCountDouble; j++) {
                doubleList.add((double) hybridStartDouble / doubleMaxPointValue);
                hybridStart += 3;
            }
            for (int j = 0; j < hybridCountDouble; j++) {
                doubleList.add((double) hybridStartDouble / doubleMaxPointValue);
            }
            hybridCountDouble += 2;
        }
    }

    @After
    public void tearDown() {}

    @Test
    public void testRLEFloat() throws Exception {
        for (int i = 1; i <= 10; i++) {
            testFloatLength(Encoding.RLE, floatList, floatMaxPointNumber, false, i);
        }
    }

    @Test
    public void testRLEDouble() throws Exception {
        for (int i = 1; i <= 10; i++) {
            testDoubleLength(Encoding.RLE, doubleList, doubleMaxPointNumber, false, i);
        }
    }

    @Test
    public void testDIFFFloat() throws Exception {
        for (int i = 1; i <= 10; i++) {
            testFloatLength(Encoding.TS_2DIFF, floatList, floatMaxPointNumber, false, i);
        }
    }

    @Test
    public void testDIFFDouble() throws Exception {
        for (int i = 1; i <= 10; i++) {
            testDoubleLength(Encoding.TS_2DIFF, doubleList, doubleMaxPointNumber, false, i);
        }
    }

    // @Test
    // public void testBigDecimal() throws Exception {
    // for (int i = 1; i <= 5; i++) {
    // testDecimalLenght(TSEncoding.TS_2DIFF, doubleList, doubleMaxPointNumber,
    // false, i);
    // testDecimalLenght(TSEncoding.RLE, doubleList, doubleMaxPointNumber, false,
    // i);
    // }
    // }

    @Test
    public void test() throws Exception {

        float value = 7.101f;
        Encoder encoder = new FloatEncoder(Encoding.RLE, DataType.FLOAT, 3);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        encoder.encode(value, baos);
        encoder.flush(baos);
        encoder.encode(value + 2, baos);
        encoder.flush(baos);
        ByteBuffer buffer = ByteBuffer.wrap(baos.toByteArray());
        Decoder decoder1 = new FloatDecoder(Encoding.RLE, DataType.FLOAT);
        Decoder decoder2 = new FloatDecoder(Encoding.RLE, DataType.FLOAT);
        float value1_ = decoder1.readFloat(buffer);
        float value2_ = decoder2.readFloat(buffer);
        assertEquals(value, value1_, delta);
        assertEquals(value + 2, value2_, delta);
        logger.debug("{} // {}", value, value1_);
        logger.debug("{} // {}", value + 2, value2_);
    }

    private void testFloatLength(
            Encoding encoding,
            List<Float> valueList,
            int maxPointValue,
            boolean isDebug,
            int repeatCount)
            throws Exception {
        Encoder encoder = new FloatEncoder(encoding, DataType.FLOAT, maxPointValue);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int i = 0; i < repeatCount; i++) {
            for (float value : valueList) {
                encoder.encode(value, baos);
            }
            encoder.flush(baos);
        }

        ByteBuffer buffer = ByteBuffer.wrap(baos.toByteArray());

        for (int i = 0; i < repeatCount; i++) {
            Decoder decoder = new FloatDecoder(encoding, DataType.FLOAT);
            for (float value : valueList) {
                float value_ = decoder.readFloat(buffer);
                if (isDebug) {
                    logger.debug("{} // {}", value_, value);
                }
                assertEquals(value, value_, delta);
            }
        }
    }

    private void testDoubleLength(
            Encoding encoding,
            List<Double> valueList,
            int maxPointValue,
            boolean isDebug,
            int repeatCount)
            throws Exception {
        Encoder encoder = new FloatEncoder(encoding, DataType.DOUBLE, maxPointValue);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int i = 0; i < repeatCount; i++) {
            for (double value : valueList) {
                encoder.encode(value, baos);
            }
            encoder.flush(baos);
        }

        ByteBuffer buffer = ByteBuffer.wrap(baos.toByteArray());

        for (int i = 0; i < repeatCount; i++) {
            Decoder decoder = new FloatDecoder(encoding, DataType.DOUBLE);
            for (double value : valueList) {
                double value_ = decoder.readDouble(buffer);
                if (isDebug) {
                    logger.debug("{} // {}", value_, value);
                }
                assertEquals(value, value_, delta);
            }
        }
    }

    // private void testDecimalLenght(TSEncoding encoding, List<Double> valueList,
    // int maxPointValue,
    // boolean isDebug, int repeatCount) throws Exception {
    // Encoder encoder = new FloatEncoder(encoding, TSDataType.BIGDECIMAL,
    // maxPointValue);
    // ByteArrayOutputStream baos = new ByteArrayOutputStream();
    // for (int i = 0; i < repeatCount; i++) {
    // for (double value : valueList) {
    // encoder.encode(new BigDecimal(value), baos);
    // }
    // encoder.flush(baos);
    // }
    // logger.debug("Repeated {} encoding done ", repeatCount);
    // ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
    //
    // for (int i = 0; i < repeatCount; i++) {
    // Decoder decoder = new FloatDecoder(encoding, TSDataType.BIGDECIMAL);
    // for (double value : valueList) {
    // double value_ = decoder.readBigDecimal(bais).doubleValue();
    // if (isDebug) {
    // logger.debug("{} // {}", value_, value);
    // }
    // assertEquals(value, value_, delta);
    // }
    // logger.debug("Repeated {} turn ", repeatCount, i);
    // }
    // }
}

