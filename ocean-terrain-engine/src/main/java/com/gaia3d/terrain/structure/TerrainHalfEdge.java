/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.structure;

import com.gaia3d.basic.geometry.GaiaRectangle;
import com.gaia3d.basic.geometry.entities.GaiaLine;
import com.gaia3d.io.BigEndianDataInputStream;
import com.gaia3d.io.BigEndianDataOutputStream;
import com.gaia3d.terrain.types.TerrainHalfEdgeType;
import com.gaia3d.terrain.types.TerrainObjectStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.joml.Vector2d;
import org.joml.Vector3d;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Getter
@Setter
public class TerrainHalfEdge {
    private int id = -1;

    private TerrainVertex startVertex = null;
    private TerrainHalfEdge next = null;
    private TerrainHalfEdge twin = null;
    private TerrainTriangle triangle = null;

    private int startVertexId = -1;
    private int nextId = -1;
    private int twinId = -1;
    private int triangleId = -1;

    private TerrainHalfEdgeType type = TerrainHalfEdgeType.UNKNOWN;
    private TerrainObjectStatus objectStatus = TerrainObjectStatus.ACTIVE;
    private TerrainLine2D line2D = null;
    private GaiaRectangle boundingRect = null;

    public void deleteObjects() {
        startVertex = null;
        next = null;
        twin = null;
        triangle = null;
        line2D = null;
    }

    public void setTwin(TerrainHalfEdge twin) {
        this.twin = twin;
        if (twin != null) {
            twin.twin = this;
        }
    }

    public void setTriangle(TerrainTriangle triangle) {
        this.triangle = triangle;

        triangle.halfEdge = this;
    }

    public void setTriangleToHEdgesLoop(TerrainTriangle triangle) {
        List<TerrainHalfEdge> halfEdgesLoop = new ArrayList<>();
        this.getHalfEdgesLoop(halfEdgesLoop);
        for (TerrainHalfEdge halfEdge : halfEdgesLoop) {
            halfEdge.setTriangle(triangle);
        }
        halfEdgesLoop.clear();
    }

    public void setStartVertex(TerrainVertex startVertex) {
        this.startVertex = startVertex;
        startVertex.setOutingHEdge(this);
    }

    public TerrainVertex getEndVertex() {
        if (next != null) {
            return next.getStartVertex();
        } else {

            if (twin != null) {
                return twin.getStartVertex();
            } else {
                return null;
            }
        }
    }

    public TerrainHalfEdge getPrev() {
        TerrainHalfEdge thisHalfEdge = this;
        TerrainHalfEdge currHalfEdge = this;
        TerrainHalfEdge prevHalfEdge = null;
        boolean finished = false;
        while (!finished) {
            TerrainHalfEdge nextHalfEdge = currHalfEdge.next;
            if (nextHalfEdge == null) {
                return null;
            }

            if (nextHalfEdge == thisHalfEdge) {
                finished = true;
                prevHalfEdge = currHalfEdge;
            } else {
                currHalfEdge = nextHalfEdge;
            }
        }

        return prevHalfEdge;
    }

    public TerrainLine2D getLine2DXY() {
        if (this.line2D == null) {
            this.line2D = new TerrainLine2D();
            Vector3d startPos = this.getStartVertex().getPosition();
            Vector3d endPos = this.getEndVertex().getPosition();
            this.line2D.setBy2Points(startPos.x, startPos.y, endPos.x, endPos.y);
        }

        return this.line2D;
    }

    public void getHalfEdgesLoop(List<TerrainHalfEdge> halfEdgesLoop) {
        TerrainHalfEdge currHalfEdge = this;
        TerrainHalfEdge firstHalfEdge = this;
        halfEdgesLoop.add(currHalfEdge);
        boolean finished = false;
        while (!finished) {
            TerrainHalfEdge nextHalfEdge = currHalfEdge.next;
            if (nextHalfEdge == null) {
                finished = true;
                break;
            } else {
                currHalfEdge = nextHalfEdge;
                if (currHalfEdge == firstHalfEdge) {
                    finished = true;
                    break;
                }
                halfEdgesLoop.add(currHalfEdge);
            }
        }
    }

    public double getSquaredLength() {
        Vector3d startPos = this.getStartVertex().getPosition();
        Vector3d endPos = this.getEndVertex().getPosition();
        return startPos.distanceSquared(endPos);
    }

    public double getSquaredLengthXY() {
        Vector3d startPos = this.getStartVertex().getPosition();
        Vector3d endPos = this.getEndVertex().getPosition();
        Vector2d startPosXY = new Vector2d(startPos.x, startPos.y);
        Vector2d endPosXY = new Vector2d(endPos.x, endPos.y);
        return startPosXY.distanceSquared(endPosXY);
    }

    public Vector3d getMidPosition() {
        Vector3d startPos = this.getStartVertex().getPosition();
        Vector3d endPos = this.getEndVertex().getPosition();
        Vector3d midPos = new Vector3d();
        midPos.add(startPos).add(endPos).mul(0.5);

        return midPos;
    }

    public Vector3d getDirection() {
        Vector3d startPos = this.getStartVertex().getPosition();
        Vector3d endPos = this.getEndVertex().getPosition();
        Vector3d dir = new Vector3d();
        endPos.sub(startPos, dir);
        dir.normalize();
        return dir;
    }

    public void getInterpolatedPositions(List<Vector3d> resultPositions, int numPositions) {

        resultPositions.clear();
        Vector3d startPos = this.getStartVertex().getPosition();
        Vector3d endPos = this.getEndVertex().getPosition();
        Vector3d dir = getDirection();
        double length = startPos.distance(endPos);
        double step = length / (numPositions - 1);
        for (int i = 0; i < numPositions; i++) {
            Vector3d pos = new Vector3d();
            pos.add(startPos).add(dir.mul(step * i));
            resultPositions.add(pos);
        }
    }

    public GaiaRectangle getBoundingRectangle() {
        if (this.boundingRect == null) {
            this.boundingRect = new GaiaRectangle();
            Vector3d startPos = this.getStartVertex().getPosition();
            Vector3d endPos = this.getEndVertex().getPosition();
            this.boundingRect.setInit(new Vector2d(startPos.x, startPos.y));
            this.boundingRect.addPoint(endPos.x, endPos.y);
        }

        return this.boundingRect;
    }

    public boolean isHalfEdgePossibleTwin(TerrainHalfEdge halfEdge, double error) {
        if (halfEdge == null || halfEdge.getObjectStatus() == TerrainObjectStatus.DELETED) {
            return false;
        }

        TerrainVertex startPoint_A = this.getStartVertex();
        TerrainVertex endPoint_A = this.getEndVertex();

        TerrainVertex startPoint_B = halfEdge.getStartVertex();
        TerrainVertex endPoint_B = halfEdge.getEndVertex();

        if (startPoint_A == null || endPoint_A == null || startPoint_B == null || endPoint_B == null) {
            log.error("Error: startPoint_A, endPoint_A, startPoint_B or endPoint_B is null");
            return false;
        }

        GaiaRectangle boundingRect_A = this.getBoundingRectangle();
        GaiaRectangle boundingRect_B = halfEdge.getBoundingRectangle();

        if (!boundingRect_A.intersectsInSomeAxis(boundingRect_B)) {
            return false;
        }

        return startPoint_A.isCoincidentVertexXY(endPoint_B, error) && startPoint_B.isCoincidentVertexXY(endPoint_A, error);
    }

    public boolean isTwineableByPointers(TerrainHalfEdge twin) {
        TerrainVertex thisStartVertex = this.getStartVertex();
        TerrainVertex thisEndVertex = this.getEndVertex();
        TerrainVertex twinStartVertex = twin.getStartVertex();
        TerrainVertex twinEndVertex = twin.getEndVertex();

        return thisStartVertex == twinEndVertex && thisEndVertex == twinStartVertex;
    }

    public boolean hasTwin() {
        if (this.twin == null) {
            return false;
        } else {
            if (this.twin.getObjectStatus() == TerrainObjectStatus.DELETED) {
                this.twin.setTwin(null);
                this.twin = null;
                return false;
            }
        }
        return true;
    }

    public void loadDataInputStream(BigEndianDataInputStream dataInputStream) throws IOException {
        this.id = dataInputStream.readInt();

        this.startVertexId = dataInputStream.readInt();
        this.nextId = dataInputStream.readInt();
        this.twinId = dataInputStream.readInt();
        this.triangleId = dataInputStream.readInt();
        int typeValue = dataInputStream.readInt();
        this.type = TerrainHalfEdgeType.fromValue(typeValue);
    }

    public void saveDataOutputStream(BigEndianDataOutputStream dataOutputStream) {
        try {

            dataOutputStream.writeInt(id);

            if (startVertex != null) {
                dataOutputStream.writeInt(startVertex.getId());
            } else {
                dataOutputStream.writeInt(-1);
            }

            if (next != null) {
                dataOutputStream.writeInt(next.id);
            } else {
                dataOutputStream.writeInt(-1);
            }

            if (twin != null) {
                dataOutputStream.writeInt(twin.id);
            } else {
                dataOutputStream.writeInt(-1);
            }

            if (triangle != null) {
                dataOutputStream.writeInt(triangle.getId());
            } else {
                dataOutputStream.writeInt(-1);
            }
            int type_int = type.getValue();
            dataOutputStream.writeInt(type_int);
        } catch (Exception e) {
            log.error("Error:", e);
        }
    }

    public GaiaLine getLine() {
        Vector3d startPos = this.getStartVertex().getPosition();
        Vector3d direction = getDirection();
        return new GaiaLine(startPos, direction);
    }

    public byte intersectsPoint(Vector3d point, double error) {

        byte intersectionType = 0;

        GaiaLine line = this.getLine();
        if (!line.intersectsPoint(point, error)) {
            return 0;
        }

        Vector3d startPoint = this.getStartVertex().getPosition();
        double dist = point.distance(startPoint);
        if (dist < error) {
            return 2;
        }

        Vector3d endPoint = this.getEndVertex().getPosition();
        double dist2 = endPoint.distance(startPoint);
        if (dist2 < error) {
            return 3;
        }

        double abx = endPoint.x - startPoint.x;
        double aby = endPoint.y - startPoint.y;
        double abz = endPoint.z - startPoint.z;

        double apx = point.x - startPoint.x;
        double apy = point.y - startPoint.y;
        double apz = point.z - startPoint.z;

        double abLenSq = abx * abx + aby * aby + abz * abz;

        if (abLenSq == 0.0) {
            double dx = point.x - startPoint.x;
            double dy = point.y - startPoint.y;
            double dz = point.z - startPoint.z;
            if ((dx * dx + dy * dy + dz * dz) < error * error) {
                return 2;
            }
        }

        double t = (apx * abx + apy * aby + apz * abz) / abLenSq;

        if (t < 0.0 || t > 1.0) {
            return 0;
        }

        double projx = startPoint.x + t * abx;
        double projy = startPoint.y + t * aby;
        double projz = startPoint.z + t * abz;

        double dx = point.x - projx;
        double dy = point.y - projy;
        double dz = point.z - projz;

        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq < error * error) {
            intersectionType = 1;
        }

        return intersectionType;
    }

}
