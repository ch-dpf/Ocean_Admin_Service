package org.ocean.admin.gis.terrain.engine.mago;

/** mago 地形引擎启动或执行失败。 */
public class MagoTerrainException extends RuntimeException {

    public MagoTerrainException(String message) {
        super(message);
    }

    public MagoTerrainException(String message, Throwable cause) {
        super(message, cause);
    }
}
