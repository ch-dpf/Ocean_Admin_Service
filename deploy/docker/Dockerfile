# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /workspace
COPY . .
RUN mvn -B -ntp -pl ocean-bootstrap -am clean package -DskipTests

# JNI 与 GDAL 核心从同一固定源码构建，业务不依赖命令行工具。
FROM eclipse-temurin:21-jdk-noble AS gdal-native

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential cmake ninja-build swig ant curl ca-certificates xz-utils \
    libproj-dev libgeotiff-dev libtiff-dev libjpeg-dev libpng-dev libgif-dev \
    libexpat1-dev libopenjp2-7-dev zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /tmp/gdal-build
COPY pom.xml /tmp/ocean-parent-pom.xml
RUN test "$(sed -n 's/.*<gdal.version>\([^<]*\)<\/gdal.version>.*/\1/p' /tmp/ocean-parent-pom.xml)" = "3.13.0" \
    && curl --fail --location --retry 3 --output /tmp/gdal.tar.xz \
       https://download.osgeo.org/gdal/3.13.0/gdal-3.13.0.tar.xz \
    && echo '1c537dd2f4d66f05534ae419bc2af495c2204ce13bb266c8cbd867dd6705f0c7  /tmp/gdal.tar.xz' | sha256sum --check \
    && tar --extract --xz --file /tmp/gdal.tar.xz --directory /tmp \
    && cmake -S /tmp/gdal-3.13.0 -B /tmp/gdal-build -G Ninja \
       -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/opt/gdal -DCMAKE_INSTALL_LIBDIR=lib \
       -DCMAKE_INSTALL_RPATH=/opt/gdal/lib -DBUILD_SHARED_LIBS=ON -DBUILD_APPS=OFF \
       -DBUILD_TESTING=OFF -DBUILD_PYTHON_BINDINGS=OFF -DBUILD_JAVA_BINDINGS=ON \
       -DGDAL_JAVA_GENERATE_JAVADOC=OFF -DGDAL_JAVA_JNI_INSTALL_DIR=lib/jni -DENABLE_GNM=OFF \
       -DGDAL_BUILD_OPTIONAL_DRIVERS=OFF -DOGR_BUILD_OPTIONAL_DRIVERS=OFF \
       -DGDAL_ENABLE_DRIVER_GTIFF=ON -DGDAL_ENABLE_DRIVER_HFA=ON \
       -DGDAL_ENABLE_DRIVER_JPEG=ON -DGDAL_ENABLE_DRIVER_PNG=ON \
       -DGDAL_ENABLE_DRIVER_GIF=ON -DGDAL_ENABLE_DRIVER_GRIB=ON \
       -DOGR_ENABLE_DRIVER_SHAPE=ON -DOGR_ENABLE_DRIVER_DXF=ON -DOGR_ENABLE_DRIVER_TAB=ON \
       -DOGR_ENABLE_DRIVER_CSV=ON -DOGR_ENABLE_DRIVER_GPX=ON -DOGR_ENABLE_DRIVER_CAD=ON \
       -DOGR_ENABLE_DRIVER_GEOJSON=ON -DGDAL_USE_CURL=OFF -DGDAL_USE_OPENJPEG=ON \
    && cmake --build /tmp/gdal-build --parallel 8 \
    && cmake --install /tmp/gdal-build

# 构建时实际加载 JNI、核对驱动及 PROJ 数据，不能只验证文件存在。
ENV LD_LIBRARY_PATH=/opt/gdal/lib
ENV GDAL_DATA=/opt/gdal/share/gdal
ENV PROJ_DATA=/usr/share/proj
RUN <<'EOF'
set -eu
install -D /tmp/gdal-build/swig/java/libgdalalljni.so /opt/gdal/lib/jni/libgdalalljni.so
cat > /tmp/GdalCheck.java <<'JAVA'
import org.gdal.gdal.Driver;
import org.gdal.gdal.gdal;
import org.gdal.osr.SpatialReference;
class GdalCheck {
    public static void main(String[] args) {
        gdal.AllRegister();
        if (!"3.13.0".equals(gdal.VersionInfo("RELEASE_NAME"))) throw new IllegalStateException("GDAL version mismatch");
        for (String name : new String[]{"GTiff","HFA","JPEG","PNG","GIF","GRIB","ESRI Shapefile","DXF","MapInfo File","CSV","GPX","CAD","GeoJSON"}) {
            if (gdal.GetDriverByName(name) == null) throw new IllegalStateException("Missing driver: " + name);
        }
        for (String name : new String[]{"GTiff","GeoJSON"}) {
            Driver driver = gdal.GetDriverByName(name);
            if (!"YES".equals(driver.GetMetadataItem("DCAP_CREATE")) && !"YES".equals(driver.GetMetadataItem("DCAP_CREATECOPY"))) throw new IllegalStateException("Driver cannot write: " + name);
        }
        SpatialReference srs = new SpatialReference();
        try {
            if (srs.ImportFromEPSG(4326) != 0) throw new IllegalStateException("PROJ database unavailable");
        } finally { srs.delete(); }
        System.out.println("GDAL_JAVA_CHECK_OK");
    }
}
JAVA
java --source 21 -Djava.library.path=/opt/gdal/lib/jni --class-path /opt/gdal/share/java/gdal-3.13.0.jar /tmp/GdalCheck.java
rm /tmp/GdalCheck.java
EOF

FROM eclipse-temurin:21-jre-noble AS gdal-runtime
ENV TZ=Asia/Shanghai
ENV GDAL_DATA=/opt/gdal/share/gdal
ENV PROJ_DATA=/usr/share/proj
ENV GIS_GDAL_NATIVE_LIBRARY_PATH=/opt/gdal/lib/jni

RUN apt-get update && apt-get install -y --no-install-recommends \
    libproj25 proj-data libgeotiff5 libtiff6 libjpeg-turbo8 libpng16-16t64 libgif7 \
    libexpat1 libopenjp2-7 libgomp1 zlib1g fontconfig libfreetype6 fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*
COPY --from=gdal-native /opt/gdal /opt/gdal
RUN echo /opt/gdal/lib > /etc/ld.so.conf.d/ocean-gdal.conf && ldconfig \
    && ldd /opt/gdal/lib/jni/libgdalalljni.so > /tmp/gdal-jni-links \
    && ! grep 'not found' /tmp/gdal-jni-links \
    && rm /tmp/gdal-jni-links

FROM gdal-runtime AS app
RUN groupadd --system ocean && useradd --system --gid ocean --home-dir /app ocean
WORKDIR /app

COPY --from=builder /workspace/ocean-bootstrap/target/ocean-bootstrap-1.0.0-SNAPSHOT.jar /app/app.jar
COPY --from=builder /workspace/db/migration /app/db/migration
COPY --from=builder /workspace/ocean-business-geo/tools/mago/mago-3d-terrainer-1.14.2-release.jar /app/runtime/mago/mago-3d-terrainer-1.14.2-release.jar

RUN mkdir -p /app/data /app/logs && chown -R ocean:ocean /app

USER ocean
EXPOSE 8090

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
