/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.quantized.mesh;

import com.gaia3d.io.LittleEndianDataInputStream;
import com.gaia3d.io.LittleEndianDataOutputStream;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;

@Getter
@Setter
public class QuantizedMeshHeader {

    private double CenterX = 0.0;
    private double CenterY = 0.0;
    private double CenterZ = 0.0;

    private float MinimumHeight = 0.0f;
    private float MaximumHeight = 0.0f;

    private double BoundingSphereCenterX = 0.0;
    private double BoundingSphereCenterY = 0.0;
    private double BoundingSphereCenterZ = 0.0;
    private double BoundingSphereRadius = 0.0;

    private double HorizonOcclusionPointX = 0.0;
    private double HorizonOcclusionPointY = 0.0;
    private double HorizonOcclusionPointZ = 0.0;

    public void loadDataInputStream(LittleEndianDataInputStream dataInputStream) throws IOException {
        CenterX = dataInputStream.readDouble();
        CenterY = dataInputStream.readDouble();
        CenterZ = dataInputStream.readDouble();

        MinimumHeight = dataInputStream.readFloat();
        MaximumHeight = dataInputStream.readFloat();

        BoundingSphereCenterX = dataInputStream.readDouble();
        BoundingSphereCenterY = dataInputStream.readDouble();
        BoundingSphereCenterZ = dataInputStream.readDouble();
        BoundingSphereRadius = dataInputStream.readDouble();

        HorizonOcclusionPointX = dataInputStream.readDouble();
        HorizonOcclusionPointY = dataInputStream.readDouble();
        HorizonOcclusionPointZ = dataInputStream.readDouble();
    }

    public void saveDataOutputStream(LittleEndianDataOutputStream dataOutputStream) throws IOException {
        dataOutputStream.writeDouble(CenterX);
        dataOutputStream.writeDouble(CenterY);
        dataOutputStream.writeDouble(CenterZ);

        dataOutputStream.writeFloat(MinimumHeight);
        dataOutputStream.writeFloat(MaximumHeight);

        dataOutputStream.writeDouble(BoundingSphereCenterX);
        dataOutputStream.writeDouble(BoundingSphereCenterY);
        dataOutputStream.writeDouble(BoundingSphereCenterZ);
        dataOutputStream.writeDouble(BoundingSphereRadius);

        dataOutputStream.writeDouble(HorizonOcclusionPointX);
        dataOutputStream.writeDouble(HorizonOcclusionPointY);
        dataOutputStream.writeDouble(HorizonOcclusionPointZ);
    }
}
