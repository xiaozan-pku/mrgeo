/*
 * Copyright 2009-2015 DigitalGlobe, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.mrgeo.utils

import java.awt.image._
import java.io.{File, IOException, InputStream, OutputStream}
import java.net.URI
import java.nio._
import java.nio.file.Files

import org.apache.commons.io.IOUtils
import org.apache.commons.lang3.ArrayUtils
import org.apache.hadoop.fs.Path
import org.apache.spark.Logging
import org.gdal.gdal.{Band, Dataset, Driver, gdal}
import org.gdal.gdalconst.gdalconstConstants
import org.gdal.osr.{CoordinateTransformation, SpatialReference, osr, osrConstants}
import org.mrgeo.core.{MrGeoConstants, MrGeoProperties}
import org.mrgeo.data.raster.RasterUtils
import org.mrgeo.hdfs.utils.HadoopFileUtils
import org.mrgeo.utils.MrGeoImplicits._

import scala.collection.JavaConversions._
import scala.util.control.Breaks



class GDALException extends IOException  {
  private var origException: Exception = null

  def this(e: Exception) {
    this()
    origException = e
  }

  def this(msg: String) {
    this()
    origException = new Exception(msg)
  }
  def this(msg: String, e:Exception) {
    this()
    origException = new Exception(msg, e)
  }

  override def printStackTrace() {
    this.origException.printStackTrace()
  }
}

object GDALUtils extends Logging {

  val EPSG4326: String = osrConstants.SRS_WKT_WGS84

  private val VSI_PREFIX: String = "/vsimem/"
  private val GDAL_PAM_ENABLED: String = "GDAL_PAM_ENABLED"

  loadLibrary()
  initializeGDAL()

  // empty method to force static initializer
  def register() = {}

  def isValidDataset(imagename: String): Boolean = {
    try {
      val image: Dataset = GDALUtils.open(imagename)
      GDALUtils.close(image)
      return true
    }
    catch {
      case ignored: IOException =>
    }

    false
  }

  def open(stream: InputStream): Dataset = {
    val imagename: String = "stream" + HadoopUtils.createRandomString(5)
    val bytes: Array[Byte] = IOUtils.toByteArray(stream)

    gdal.FileFromMemBuffer(VSI_PREFIX + imagename, bytes)

    val image = gdal.Open(VSI_PREFIX + imagename)
    if (image != null) {
      logDebug("  Image loaded successfully: " + imagename)
      return image
    }

    logInfo(
      "Image not loaded, but unfortunately no exceptions were thrown, look for a logged explanation somewhere above")
    null
  }

  def createEmptyMemoryRaster(src: Dataset, width: Int, height: Int): Dataset = {

    val bands: Int = src.getRasterCount
    var datatype: Int = -1

    val nodatas = Array.newBuilder[Double]

    val nodata = Array.ofDim[java.lang.Double](1)
    for (i <- 1 to src.GetRasterCount()) {
      val band: Band = src.GetRasterBand(i)
      if (datatype < 0) {
        datatype = band.getDataType
      }

      band.GetNoDataValue(nodata)
      nodatas += nodata(0)
    }

    createEmptyMemoryRaster(width, height, bands, datatype, nodatas.result())
  }

  def createEmptyMemoryRaster(width: Int, height: Int, bands: Int, datatype: Int, nodatas: Array[Double] = null):Dataset = {
    val driver: Driver = gdal.GetDriverByName("MEM")

    val dataset = driver.Create("InMem", width, height, bands, datatype)

    if (dataset != null) {
      if (nodatas != null) {
        for (i <- 1 to dataset.getRasterCount) {
          val nodata: Double = nodatas(i - 1)
          val band: Band = dataset.GetRasterBand(i)
          band.Fill(nodata)
          band.SetNoDataValue(nodata)
        }
      }
      return dataset
    }

    null
  }

  def toDataset(raster: Raster, nodata: Double = Double.NegativeInfinity): Dataset = {
    val nodatas = if (nodata == Double.NegativeInfinity) null else Array.fill[Double](raster.getNumBands)(nodata)
    val datatype = toGDALDataType(raster.getTransferType)

    val ds = GDALUtils.createEmptyMemoryRaster(raster.getWidth, raster.getHeight, raster.getNumBands, datatype, nodatas)

    if (ds != null) {
      copyToDataset(ds, raster)
    }

    ds
  }

  def toGDALDataType(rasterType: Int): Int = {
    rasterType match {
    case DataBuffer.TYPE_BYTE => gdalconstConstants.GDT_Byte
    case DataBuffer.TYPE_SHORT => gdalconstConstants.GDT_Int16
    case DataBuffer.TYPE_USHORT => gdalconstConstants.GDT_UInt16
    case DataBuffer.TYPE_INT => gdalconstConstants.GDT_Int32
    case DataBuffer.TYPE_FLOAT => gdalconstConstants.GDT_Float32
    case DataBuffer.TYPE_DOUBLE => gdalconstConstants.GDT_Float64
    case _ => gdalconstConstants.GDT_Unknown
    }
  }

  def toRasterDataBufferType(gdaldatatype: Int): Int = {
    gdaldatatype match {
    case gdalconstConstants.GDT_Byte => DataBuffer.TYPE_BYTE
    case gdalconstConstants.GDT_UInt16 => DataBuffer.TYPE_USHORT
    case gdalconstConstants.GDT_Int16 => DataBuffer.TYPE_SHORT
    case gdalconstConstants.GDT_UInt32 => DataBuffer.TYPE_INT
    case gdalconstConstants.GDT_Int32 => DataBuffer.TYPE_INT
    case gdalconstConstants.GDT_Float32 => DataBuffer.TYPE_FLOAT
    case gdalconstConstants.GDT_Float64 => DataBuffer.TYPE_DOUBLE
    case _ => DataBuffer.TYPE_UNDEFINED
    }
  }

  def toRaster(image: Dataset):Raster = {
    val bands: Int = image.getRasterCount

    val bandlist = Array.range(1, image.getRasterCount + 1)

    val datatype = image.GetRasterBand(1).getDataType
    val pixelsize = gdal.GetDataTypeSize(datatype) / 8

    val width = image.getRasterXSize
    val height = image.getRasterYSize

    val pixelstride = pixelsize * bands
    val linestride = pixelstride * width

    val rastersize = linestride * height

    val data: ByteBuffer = ByteBuffer.allocateDirect(rastersize)
    data.order(ByteOrder.nativeOrder)

    // read the data interleaved (it _should_ be much more efficient reading)
    image.ReadRaster_Direct(0, 0, width, height, width, height, datatype, data,
      bandlist, pixelstride, linestride, pixelsize)

    data.rewind

    toRaster(height, width, bands, datatype, data)
  }

  def toRaster(height: Int, width: Int, bands: Int, gdaldatatype: Int, data: Array[Byte]): Raster = {
    toRaster(height, width, bands, gdaldatatype, ByteBuffer.wrap(data))
  }

  def toRaster(height: Int, width: Int, bands: Int, gdaldatatype: Int, data: ByteBuffer): Raster = {
    val datatype = toRasterDataBufferType(gdaldatatype)
    val bandbytes = height * width * (gdal.GetDataTypeSize(gdaldatatype) / 8)

    val databytes = bandbytes * bands
    val bandoffsets = Array.range(0, bands)

    // keep the raster interleaved
    val sm = new PixelInterleavedSampleModel(datatype, width, height, bands, bands * width, bandoffsets)

    val db =
      datatype match {
      case DataBuffer.TYPE_BYTE =>
        val bytedata: Array[Byte] = new Array[Byte](databytes)
        data.get(bytedata)
        new DataBufferByte(bytedata, bytedata.length)
      case DataBuffer.TYPE_FLOAT =>
        val floatbuff: FloatBuffer = data.asFloatBuffer
        val floatdata: Array[Float] = new Array[Float](databytes / RasterUtils.FLOAT_BYTES)
        floatbuff.get(floatdata)
        new DataBufferFloat(floatdata, floatdata.length)
      case DataBuffer.TYPE_DOUBLE =>
        val doublebuff: DoubleBuffer = data.asDoubleBuffer
        val doubledata: Array[Double] = new Array[Double](databytes / RasterUtils.DOUBLE_BYTES)
        doublebuff.get(doubledata)
        new DataBufferDouble(doubledata, doubledata.length)
      case DataBuffer.TYPE_INT =>
        val intbuff: IntBuffer = data.asIntBuffer
        val intdata: Array[Int] = new Array[Int](databytes / RasterUtils.INT_BYTES)
        intbuff.get(intdata)
        new DataBufferInt(intdata, intdata.length)
      case DataBuffer.TYPE_SHORT =>
        val shortbuff: ShortBuffer = data.asShortBuffer
        val shortdata: Array[Short] = new Array[Short](databytes / RasterUtils.SHORT_BYTES)
        shortbuff.get(shortdata)
        new DataBufferShort(shortdata, shortdata.length)
      case DataBuffer.TYPE_USHORT =>
        val ushortbuff: ShortBuffer = data.asShortBuffer
        val ushortdata: Array[Short] = new Array[Short](databytes / RasterUtils.SHORT_BYTES)
        ushortbuff.get(ushortdata)
        new DataBufferUShort(ushortdata, ushortdata.length)
      case _ =>
        throw new GDALException("Error trying to read raster.  Bad raster data type")
      }

    Raster.createWritableRaster(sm, db, null)
  }

  def swapBytes(bytes: Array[Byte], gdaldatatype: Int) = {

    var tmp: Byte = 0
    gdaldatatype match {
      // 2 byte value... swap byte 1 with 2
    case gdalconstConstants.GDT_UInt16 | gdalconstConstants.GDT_Int16 =>
      for (i <- bytes.indices by 2) {
        tmp = bytes(i)
        bytes(i) = bytes(i + 1)
        bytes(i + 1) = tmp
      }
    // 4 byte value... swap bytes 1 & 4, 2 & 3
    case gdalconstConstants.GDT_UInt32 | gdalconstConstants.GDT_Int32 | gdalconstConstants.GDT_Float32 =>
      for (i <- bytes.indices by 4) {
        // swap 0 & 3
        tmp = bytes(i)
        bytes(i) = bytes(i + 3)
        bytes(i + 3) = tmp

        // swap 1 & 2
        tmp = bytes(i + 1)
        bytes(i + 1) = bytes(i + 2)
        bytes(i + 2) = tmp
      }
    // 8 byte value... swap bytes 1 & 8, 2 & 7, 3 & 6, 4 & 5
    case gdalconstConstants.GDT_Float64 =>
      for (i <- bytes.indices by 4) {
        // swap 0 & 7
        tmp = bytes(i)
        bytes(i) = bytes(i + 7)
        bytes(i + 7) = tmp

        // swap 1 & 6
        tmp = bytes(i + 1)
        bytes(i + 1) = bytes(i + 6)
        bytes(i + 6) = tmp

        // swap 2 & 5
        tmp = bytes(i + 2)
        bytes(i + 2) = bytes(i + 5)
        bytes(i + 5) = tmp

        // swap 3 & 4
        tmp = bytes(i + 3)
        bytes(i + 3) = bytes(i + 4)
        bytes(i + 4) = tmp
      }
    }
  }

  def getnodatas(imagename: String): Array[Number] = {
    val ds = open(imagename)
    if (ds != null) {
      try {
        return getnodatas(ds)
      }
      finally {
        close(ds)
      }
    }
    throw new GDALException("Error opening image: " + imagename)
  }

  def getnodatas(image: Dataset): Array[Number] = {
    val bands = image.GetRasterCount

    val nodatas = Array.fill[Double](bands)(Double.NaN)

    val v = new Array[java.lang.Double](1)
    for (i <- 1 to bands) {
      val band: Band = image.GetRasterBand(i)
      band.GetNoDataValue(v)
      if (v(0) != null) {
        nodatas(i - 1) = v(0)
      }
    }

    nodatas
  }


  def open(imagename: String): Dataset = {
    try {
      val uri: URI = new URI(imagename)
      logDebug("Loading image with GDAL: " + imagename)

      val file: File = new File(uri.getPath)
      if (file.exists) {
        val image = gdal.Open(file.getCanonicalPath)
        if (image != null) {
          logDebug("  Image loaded successfully: " + imagename)
          return image
        }
      }

      val p = new Path(uri)
      val fs = HadoopFileUtils.getFileSystem(p)
      val is = fs.open(p)

      val bytes = IOUtils.toByteArray(is)

      val vsiname = VSI_PREFIX + imagename
      gdal.FileFromMemBuffer(vsiname, bytes)

      val image = gdal.Open(vsiname)
      if (image != null) {
        logDebug("  Image loaded successfully: " + imagename)
        return image
      }

      logInfo(
        "Image not loaded, but unfortunately no exceptions were thrown, look for a logged explanation somewhere above")
    }
    catch {
      case e: Exception => throw new GDALException("Error opening image file: " + imagename, e)
    }

    null
  }

  def close(image: Dataset) {
    val files = image.GetFileList

    image.delete()

    // unlink the file from memory if is has been streamed
    for (f <- files) {
      f match {
      case file: String =>
        if (file.startsWith(VSI_PREFIX)) gdal.Unlink(file)
      case _ =>
      }
    }
  }


  def calculateZoom(imagename: String, tilesize: Int): Int = {
    try {
      val image = GDALUtils.open(imagename)
      if (image != null) {
        val b = getBounds(image)
        val px = b.getWidth / image.GetRasterXSize
        val py = b.getHeight / image.GetRasterYSize
        val zx = TMSUtils.zoomForPixelSize(Math.abs(px), tilesize)
        val zy = TMSUtils.zoomForPixelSize(Math.abs(py), tilesize)

        GDALUtils.close(image)
        if (zx > zy) {
          return zx
        }
        return zy
      }
    }
    catch {
      case ignored: IOException =>
    }
    -1
  }

  def getBounds(image: Dataset): Bounds = {
    val xform = image.GetGeoTransform

    val srs = new SpatialReference(image.GetProjection)
    val dst = new SpatialReference(EPSG4326)

    val tx = new CoordinateTransformation(srs, dst)

    val w = image.GetRasterXSize
    val h = image.GetRasterYSize

    var c1: Array[Double] = null
    var c2: Array[Double] = null
    var c3: Array[Double] = null
    var c4: Array[Double] = null

    if (tx != null) {
      c1 = tx.TransformPoint(xform(0), xform(3))
      c2 = tx.TransformPoint(xform(0) + xform(1) * w, xform(3) + xform(5) * h)
      c3 = tx.TransformPoint(xform(0) + xform(1) * w, xform(3))
      c4 = tx.TransformPoint(xform(0), xform(3) + xform(5) * h)
    }
    else {
      c1 = Array[Double](xform(0), xform(3))
      c2 = Array[Double](xform(0) + xform(1) * w, xform(3) + xform(5) * h)
      c3 = Array[Double](xform(0) + xform(1) * w, xform(3))
      c4 = Array[Double](xform(0), xform(3) + xform(5) * h)
    }

    new Bounds(Math.min(Math.min(c1(0), c2(0)), Math.min(c3(0), c4(0))),
      Math.min(Math.min(c1(1), c2(1)), Math.min(c3(1), c4(1))),
      Math.max(Math.max(c1(0), c2(0)), Math.max(c3(0), c4(0))),
      Math.max(Math.max(c1(1), c2(1)), Math.max(c3(1), c4(1))))
  }

  def saveRaster(raster:Either[Raster, Dataset], output:Either[String, OutputStream],
      bounds:Either[Bounds, TMSUtils.Bounds] = null, nodata:Double = Double.NegativeInfinity,
      format:String = "GTiff", options:Array[String] = Array.empty[String]): Unit =  {

    val filename = output match {
    case Left(f) => f
    case Right(stream) => File.createTempFile("tmp-file", "").getCanonicalPath
    }

    val bnds = if (bounds == null) {
      null
    }
    else {
      bounds match {
      case Left(b) => TMSUtils.Bounds.asTMSBounds(b)
      case Right(t) => t
      }
    }

    val dataset = raster match {
    case Left(r) =>
      val ds = toDataset(r, nodata)

      val xform = new Array[Double](6)

      if (bnds != null) {

        xform(0) = bnds.w
        xform(1) = bnds.width / ds.getRasterXSize
        xform(2) = 0
        xform(3) = bnds.n
        xform(4) = 0
        xform(5) = -bnds.height / ds.getRasterYSize

        ds.SetGeoTransform(xform)
        ds.SetProjection(GDALUtils.EPSG4326)
      }
      else
      {
        xform(0) = 0
        xform(1) = ds.getRasterXSize
        xform(2) = 0
        xform(3) = 0
        xform(4) = 0
        xform(5) = -ds.getRasterYSize
      }

      ds
    case Right(d) => d
    }

    saveRaster(dataset, filename, bnds, format, options)

    output match {
    case Right(stream) =>
      Files.copy(new File(filename).toPath, stream)
      stream.flush()
      if (! new File(filename).delete()) {
        throw new IOException("Error deleting temporary file: " + filename)
      }
    case _ =>
    }

    raster match {
    case Left(r) => dataset.delete()
    case Right(d) =>
    }

  }

  def saveRasterTile(raster:Either[Raster, Dataset], output:Either[String, OutputStream],
      tx:Long, ty:Long, zoom:Int, nodata:Double = Double.NegativeInfinity,
      format:String = "GTiff", options:Array[String] = Array.empty[String]): Unit = {

    val tilesize = raster match {
    case Left(r) => r.getWidth
    case Right(d) => d.getRasterXSize
    }

    val bounds = TMSUtils.tileBounds(tx, ty, zoom, tilesize)

    saveRaster(raster, output, bounds, nodata, format, options)
  }

  private def copyToDataset(ds: Dataset, raster: Raster) {
    val datatype = GDALUtils.toGDALDataType(raster.getTransferType)
    val bands = raster.getNumBands

    val width = raster.getWidth
    val height = raster.getHeight

    val bandlist = Array.range(1, raster.getNumBands + 1)

    val pixelsize = gdal.GetDataTypeSize(datatype) / 8
    val pixelstride = pixelsize * bands
    val linestride = pixelstride * width
    val bandstride = pixelsize

    ds.SetProjection(GDALUtils.EPSG4326)

    val imagesize = pixelsize.toLong * linestride * height
    if (imagesize < 2147483648L) {
      val elements = raster.getDataElements(raster.getMinX, raster.getMinY, raster.getWidth, raster.getHeight, null)

      val bytes = ByteBuffer.allocateDirect(imagesize.toInt)
      bytes.order(ByteOrder.nativeOrder)

      elements match {
      case bb: Array[Byte] => bytes.put(bb)
      case sb: Array[Short] => bytes.asShortBuffer().put(sb)
      case ib: Array[Int] => bytes.asIntBuffer().put(ib)
      case fb: Array[Float] => bytes.asFloatBuffer().put(fb)
      case db: Array[Double] => bytes.asDoubleBuffer().put(db)
      }

      bytes.rewind()
      ds.WriteRaster_Direct(0, 0, width, height, width, height, datatype, bytes, bandlist,
        pixelstride, linestride, bandstride)
    }
    else {
      val bytes: ByteBuffer = ByteBuffer.allocateDirect(linestride.toInt)
      bytes.order(ByteOrder.nativeOrder)
      for (y <- 0 until height) {
        bytes.rewind()
        val elements: AnyRef = raster.getDataElements(raster.getMinX, raster.getMinY + y, raster.getWidth, 1, null)
        elements match {
        case bb: Array[Byte] => bytes.put(bb)
        case sb: Array[Short] => bytes.asShortBuffer().put(sb)
        case ib: Array[Int] => bytes.asIntBuffer().put(ib)
        case fb: Array[Float] => bytes.asFloatBuffer().put(fb)
        case db: Array[Double] => bytes.asDoubleBuffer().put(db)
        }
        ds.WriteRaster_Direct(0, y, width, 1, width, 1, datatype, bytes, bandlist, pixelstride, linestride,
          bandstride)
      }
    }
  }

  private def loadLibrary() = {
    val libs: Array[String] = Array("libgdaljni.so", "libgdalconstjni.so", "libosrjni.so", "libgdal.so")

    val rawPath: String = MrGeoProperties.getInstance.getProperty(MrGeoConstants.GDAL_PATH, null)
    if (rawPath != null) {
      // gdal jars to load.  In the order they should be loaded...
      val paths = rawPath.split(":")

      logDebug("Looking for GDAL Libraries in " + rawPath)
      libs.foreach(lib => {
        val break = new Breaks

        break.breakable({
          paths.foreach(path => {
            val file = new File(path, lib)
            val msg = " Looking for: " + file.getCanonicalPath
            if (file.exists()) {
              System.load(file.getCanonicalPath)
              logDebug(msg + " found")
              break.break()
            }
            else {
              logDebug(msg + " not found")
            }
          })

          logError("ERROR!!! Can not find gdal library " + lib + " in path " + rawPath)
        })
      })
    }
    else {
      logWarning("Can't load GDAL libraries, " + MrGeoConstants.GDAL_PATH +
          " not found in the mrgeo configuration.  This may or may not be a problem.")

      System.loadLibrary("gdal")
    }
  }

  private def initializeGDAL() = {
    osr.UseExceptions()

    if (gdal.GetDriverCount == 0) {
      gdal.AllRegister()
    }

    val drivers: Int = gdal.GetDriverCount
    if (drivers == 0) {
      log.error("GDAL libraries were not loaded!  This probibly an error.")
    }

    if (log.isDebugEnabled) {
      log.debug("GDAL Drivers supported:")

      for (i <- 0 until drivers) {
        val driver: Driver = gdal.GetDriver(i)
        logDebug("  " + driver.getLongName + "(" + driver.getShortName + ")")
      }

      log.debug("GDAL Projections supported:")
      for (o <- osr.GetProjectionMethods) {
        log.debug("  " + o)
      }
    }
  }

  private def saveRaster(ds:Dataset, file:String, bounds:TMSUtils.Bounds,
      format:String, options:Array[String]): Unit = {
    val fmt = mapType(format)
    val driver = gdal.GetDriverByName(fmt)

    val pamEnabled = gdal.GetConfigOption(GDAL_PAM_ENABLED)
    gdal.SetConfigOption(GDAL_PAM_ENABLED, "NO")

    val moreoptions = fmt.toLowerCase match {
    case "gtiff" =>
      var moreoptions = ArrayUtils.add(options, "INTERLEAVE=BAND")
      moreoptions = ArrayUtils.add(moreoptions, "COMPRESS=DEFLATE")
      moreoptions = ArrayUtils.add(moreoptions, "PREDICTOR=1")
      moreoptions = ArrayUtils.add(moreoptions, "ZLEVEL=6")
      moreoptions = ArrayUtils.add(moreoptions, "TILES=YES")
      moreoptions = ArrayUtils
          .add(moreoptions, "BLOCKXSIZE=" + (if (ds.getRasterXSize < 2048) ds.getRasterXSize else 2048))
      moreoptions = ArrayUtils
          .add(moreoptions, "BLOCKYSIZE=" + (if (ds.getRasterYSize < 2048) ds.getRasterYSize else 2048))

      moreoptions
    case _ => options
    }

    val copy: Dataset = driver.CreateCopy(file, ds, 1, moreoptions)

    if (pamEnabled != null) {
      gdal.SetConfigOption(GDAL_PAM_ENABLED, pamEnabled)
    }

    if (copy == null) {
      val errno: Int = gdal.GetLastErrorNo
      val error: Int = gdal.GetLastErrorType
      val msg: String = gdal.GetLastErrorMsg
      throw new GDALException("Error saving raster: " + file + "(" + errno + ": " + error + ": " + msg + ")")
    }

    copy.delete()
  }

  private def mapType(format: String): String = {
    format.toLowerCase match {
    case "jpg" => "jpeg"
    case "tiff" |
         "tif" |
         "geotiff" |
         "geotif" |
         "gtif" => "GTiff"
    case _ => format
    }
  }
}


