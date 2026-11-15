/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.structure;

import com.gaia3d.io.BigEndianDataInputStream;
import com.gaia3d.io.BigEndianDataOutputStream;
import com.gaia3d.terrain.types.TerrainObjectStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Slf4j
public class TerrainVertex {
    private TerrainHalfEdge outingHEdge = null;
    private Vector3d position = new Vector3d();
    private Vector3f normal = null;
    private int id = -1;
    private int outingHEdgeId = -1;
    private TerrainObjectStatus objectStatus = TerrainObjectStatus.ACTIVE;

    public void deleteObjects() {
        outingHEdge = null;
        position = null;
    }

    public boolean isCoincidentVertex(TerrainVertex vertex, double error) {
        if (vertex == null) {
            return false;
        }

        return Math.abs(this.getPosition().x - vertex.getPosition().x) < error && Math.abs(this.getPosition().y - vertex.getPosition().y) < error && Math.abs(this.getPosition().z - vertex.getPosition().z) < error;
    }

    public boolean isCoincidentVertexXY(TerrainVertex vertex, double error) {
        if (vertex == null) {
            return false;
        }

        return Math.abs(this.getPosition().x - vertex.getPosition().x) < error && Math.abs(this.getPosition().y - vertex.getPosition().y) < error;
    }

    public void avoidOutingHalfEdge(TerrainHalfEdge avoidOutingHalfEdge) {

        if (this.outingHEdge == null) {
            log.debug("Cannot avoid outingHalfEdge for vertex {} - no outingHEdge exists", this.id);
            return;
        }

        if (this.outingHEdge != avoidOutingHalfEdge) {
            return;
        }

        List<TerrainHalfEdge> allOutingHalfEdges = this.getAllOutingHalfEdges();
        for (TerrainHalfEdge outingHalfEdge : allOutingHalfEdges) {
            if (outingHalfEdge != avoidOutingHalfEdge) {
                this.outingHEdge = outingHalfEdge;
                break;
            }
        }
    }

    public void calculateNormal() {
        calculateNormal(new ArrayList<>());
    }

    public void calculateNormal(List<TerrainHalfEdge> outingHalfEdges) {
        if (this.normal == null) {
            this.normal = new Vector3f();
        }

        this.normal.set(0, 0, 0);

        if (this.outingHEdge == null) {
            log.warn("Cannot calculate normal for vertex {} - no outingHEdge. Using default normal.", this.id);
            this.normal.set(0, 0, 1);
            return;
        }

        getAllOutingHalfEdges(outingHalfEdges);
        for (TerrainHalfEdge outingHalfEdge : outingHalfEdges) {
            TerrainTriangle triangle = outingHalfEdge.getTriangle();
            if (triangle == null) {
                continue;
            }

            Vector3f normal = triangle.getNormal();
            if (normal == null) {
                continue;
            }

            this.normal.add(normal);
        }

        if (this.normal.equals(0, 0, 0)) {
            log.warn("This vertex has no normal. id : {}", this.id);
            this.normal.set(0, 0, 1);
        }

        this.normal.normalize();
    }

    public List<TerrainHalfEdge> getAllOutingHalfEdges() {
        List<TerrainHalfEdge> outingHalfEdges = new ArrayList<>();
        return getAllOutingHalfEdges(outingHalfEdges);
    }

    public List<TerrainHalfEdge> getAllOutingHalfEdges(List<TerrainHalfEdge> outingHalfEdges) {
        outingHalfEdges.clear();

        if (this.outingHEdge == null) {

            log.warn("This vertex has no outingHEdge. id : {}", this.id);
            return outingHalfEdges;
        }

        if (this.outingHEdge.getObjectStatus() == TerrainObjectStatus.DELETED) {
            log.warn("This outingHEdge is DELETED. id : {}", this.id);
            return outingHalfEdges;
        }

        TerrainHalfEdge firstHalfEdge = this.outingHEdge;
        TerrainHalfEdge currHalfEdge = this.outingHEdge;

        outingHalfEdges.add(this.outingHEdge);
        boolean finished = false;
        boolean isInteriorVertex = true;
        int counter = 0;
        int maxIterations = 100;
        while (!finished) {
            TerrainHalfEdge twinHalfEdge = currHalfEdge.getTwin();
            if (twinHalfEdge == null) {
                finished = true;
                isInteriorVertex = false;
                break;
            }
            TerrainHalfEdge nextHalfEdge = twinHalfEdge.getNext();
            if (nextHalfEdge == null) {
                finished = true;
                isInteriorVertex = false;
                break;
            } else if (nextHalfEdge == firstHalfEdge) {
                finished = true;
                break;
            }

            if (containsIdentity(outingHalfEdges, nextHalfEdge)) {
                log.warn("This vertex has duplicated outing halfEdges. id : {}, halfEdge id : {}", this.id, nextHalfEdge.getId());
                break;
            } else {
                outingHalfEdges.add(nextHalfEdge);
            }

            currHalfEdge = nextHalfEdge;

            counter++;
            if (counter > 20) {
                log.info("Info : This vertex has more than 20 outing halfEdges. id : {}", this.id);
                break;
            }

            if (counter >= maxIterations) {
                log.warn("Vertex {} has exceeded maximum iteration limit ({}) for outgoing half-edges. " +
                                "Mesh topology may be corrupted. Breaking loop.",
                        this.id, maxIterations);
                finished = true;
                break;
            }
        }

        if (!isInteriorVertex) {

            currHalfEdge = this.outingHEdge;
            finished = false;
            while (!finished) {
                TerrainHalfEdge prevHalfEdge = currHalfEdge.getPrev();
                if (prevHalfEdge == null || prevHalfEdge.getObjectStatus() == TerrainObjectStatus.DELETED) {
                    finished = true;
                    break;
                }
                TerrainHalfEdge twinHalfEdge = prevHalfEdge.getTwin();
                if (twinHalfEdge == null || twinHalfEdge.getObjectStatus() == TerrainObjectStatus.DELETED) {
                    finished = true;
                    break;
                }
                outingHalfEdges.add(twinHalfEdge);
                if (outingHalfEdges.size() > 4) {
                    break;
                }
                currHalfEdge = twinHalfEdge;
            }
        }
        return outingHalfEdges;
    }

    private boolean containsIdentity(List<TerrainHalfEdge> halfEdges, TerrainHalfEdge candidate) {
        for (TerrainHalfEdge halfEdge : halfEdges) {
            if (halfEdge == candidate) {
                return true;
            }
        }
        return false;
    }

    public TopologyValidationResult validateTopology(int maxExpectedEdges) {
        if (this.outingHEdge == null) {
            log.warn("Vertex {} has no outingHEdge during validation", this.id);
            return new TopologyValidationResult(false, 0, false,
                    TopologyValidationResult.LoopClosureType.CORRUPTED, false);
        }

        if (this.outingHEdge.getObjectStatus() == TerrainObjectStatus.DELETED) {
            log.warn("Vertex {} has DELETED outingHEdge during validation", this.id);
            return new TopologyValidationResult(false, 0, false,
                    TopologyValidationResult.LoopClosureType.CORRUPTED, false);
        }

        TerrainHalfEdge firstHalfEdge = this.outingHEdge;
        TerrainHalfEdge currHalfEdge = this.outingHEdge;
        int edgeCount = 1;
        boolean finished = false;
        boolean isInteriorVertex = true;
        boolean hitIterationLimit = false;
        int maxIterations = 100;

        while (!finished) {
            TerrainHalfEdge twinHalfEdge = currHalfEdge.getTwin();
            if (twinHalfEdge == null) {
                finished = true;
                isInteriorVertex = false;
                break;
            }
            TerrainHalfEdge nextHalfEdge = twinHalfEdge.getNext();
            if (nextHalfEdge == null) {
                finished = true;
                isInteriorVertex = false;
                break;
            } else if (nextHalfEdge == firstHalfEdge) {

                finished = true;
                break;
            }

            edgeCount++;
            currHalfEdge = nextHalfEdge;

            if (edgeCount >= maxIterations) {
                log.warn("Vertex {} exceeded max iterations ({}) during validation - likely corrupted topology",
                        this.id, maxIterations);
                hitIterationLimit = true;
                finished = true;
                break;
            }
        }

        TopologyValidationResult.LoopClosureType closureType;
        if (hitIterationLimit) {
            closureType = TopologyValidationResult.LoopClosureType.CORRUPTED;
        } else if (isInteriorVertex) {
            closureType = TopologyValidationResult.LoopClosureType.INTERIOR;
        } else {
            closureType = TopologyValidationResult.LoopClosureType.BOUNDARY;
        }

        boolean hasMultipleLoops = hitIterationLimit;
        boolean isValid = !hitIterationLimit && edgeCount <= maxExpectedEdges;

        return new TopologyValidationResult(isValid, edgeCount, hasMultipleLoops,
                closureType, hitIterationLimit);
    }

    public void saveDataOutputStream(BigEndianDataOutputStream dataOutputStream) {
        try {

            dataOutputStream.writeInt(id);
            dataOutputStream.writeDouble(position.x);
            dataOutputStream.writeDouble(position.y);
            dataOutputStream.writeDouble(position.z);

            if (outingHEdge != null) {
                dataOutputStream.writeInt(outingHEdge.getId());
            } else {
                dataOutputStream.writeInt(-1);
            }
        } catch (Exception e) {
            log.error("Error:", e);
        }
    }

    public void loadDataInputStream(BigEndianDataInputStream dataInputStream) throws IOException {
        this.id = dataInputStream.readInt();
        this.getPosition().x = dataInputStream.readDouble();
        this.getPosition().y = dataInputStream.readDouble();
        this.getPosition().z = dataInputStream.readDouble();

        this.outingHEdgeId = dataInputStream.readInt();
    }

        public record TopologyValidationResult(boolean isValid, int edgeCount, boolean hasMultipleLoops, LoopClosureType loopClosureType, boolean hitIterationLimit) {

        public enum LoopClosureType {
                INTERIOR,
                BOUNDARY,
                CORRUPTED
            }
        }
}
