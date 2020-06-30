// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.mapwithai.backend;

import static org.junit.Assume.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.openstreetmap.josm.TestUtils;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WaySegment;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.mapwithai.testutils.MapWithAITestRules;
import org.openstreetmap.josm.testutils.JOSMTestRules;
import org.openstreetmap.josm.tools.Geometry;

import com.github.tomakehurst.wiremock.WireMockServer;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class GetDataRunnableTest {
    @Rule
    @SuppressFBWarnings("URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD")
    public JOSMTestRules rule = new MapWithAITestRules().sources().wiremock().projection().fakeAPI().territories();

    public static String getDefaultMapWithAIAPIForTest(WireMockServer wireMock, String url) {
        return getDefaultMapWithAIAPIForTest(wireMock, url, "https://www.mapwith.ai");
    }

    public static String getDefaultMapWithAIAPIForTest(WireMockServer wireMock, String url, String wireMockReplace) {
        return url.replace(wireMockReplace, wireMock.baseUrl());
    }

    @Test
    public void testAddMissingElement() {
        Way way1 = TestUtils.newWay("", new Node(new LatLon(-5.7117803, 34.5011898)),
                new Node(new LatLon(-5.7111915, 34.5013994)), new Node(new LatLon(-5.7104175, 34.5016354)));
        Way way2 = new Way();
        way2.setNodes(way1.getNodes());
        way2.addNode(1, new Node(new LatLon(-5.7115826, 34.5012438)));
        Map<WaySegment, List<WaySegment>> map = GetDataRunnable.checkWayDuplications(way1, way2);
        GetDataRunnable.addMissingElement(map.entrySet().iterator().next());

        assertEquals(4, way1.getNodesCount());
        assertEquals(4, way2.getNodesCount());
        assertSame(way2.getNode(1), way2.getNode(1));
        way1.removeNode(way1.getNode(1));

        List<Node> nodes = way2.getNodes();
        Collections.reverse(nodes);
        way2.setNodes(nodes);

        map = GetDataRunnable.checkWayDuplications(way1, way2);
        GetDataRunnable.addMissingElement(map.entrySet().iterator().next());

        assumeTrue(Math.abs(Geometry.getDistance(new Node(new LatLon(0, 0)), new Node(new LatLon(0, 1)))
                * ProjectionRegistry.getProjection().getMetersPerUnit() - 111_319.5) < 0.5);
        assertEquals(4, way1.getNodesCount());
        assertEquals(4, way2.getNodesCount());
        assertTrue(way1.getNodes().containsAll(way2.getNodes()));
    }

    @Test
    public void testCleanupArtifacts() {
        Way way1 = TestUtils.newWay("", new Node(new LatLon(0, 0)), new Node(new LatLon(1, 1)));
        Way way2 = TestUtils.newWay("", way1.firstNode(), new Node(new LatLon(-1, -1)));
        DataSet ds = new DataSet();
        way1.getNodes().forEach(ds::addPrimitive);
        ds.addPrimitive(way1);
        way2.getNodes().stream().filter(node -> node.getDataSet() == null).forEach(ds::addPrimitive);
        ds.addPrimitive(way2);

        GetDataRunnable.cleanupArtifacts(way1);

        assertEquals(2, ds.getWays().parallelStream().filter(way -> !way.isDeleted()).count());

        Node tNode = new Node(way1.lastNode(), true);
        ds.addPrimitive(tNode);
        way2.addNode(tNode);

        GetDataRunnable.cleanupArtifacts(way1);
        assertEquals(1, ds.getWays().parallelStream().filter(way -> !way.isDeleted()).count());
    }

    @Test
    public void testRegressionTicket46() {
        DataSet ds = new DataSet();
        GetDataRunnable getData = new GetDataRunnable(
                Arrays.asList(new BBox(-5.7400005, 34.4524384, -5.6686014, 34.5513153)), ds, null);
        getData.setMaximumDimensions(5_000);
        getData.fork().join();
        assertNotNull(ds);
        assertFalse(ds.isEmpty());
        assertFalse(ds.allNonDeletedPrimitives().isEmpty());
    }

    @Test
    public void testAlreadyAddedElements() {
        Way addedWay = TestUtils.newWay("", new Node(new LatLon(0, 0)), new Node(new LatLon(1, 1)));
        Way duplicateWay = TestUtils.newWay("", new Node(new LatLon(0, 0)), new Node(new LatLon(1, 1)));
        Way nonDuplicateWay = TestUtils.newWay("", new Node(new LatLon(0, 0)), new Node(new LatLon(1, 2)));

        DataSet osm = new DataSet();
        addedWay.getNodes().forEach(osm::addPrimitive);
        osm.addPrimitive(addedWay);

        DataSet conflationDs = new DataSet();
        for (Way way : Arrays.asList(duplicateWay, nonDuplicateWay)) {
            way.getNodes().forEach(conflationDs::addPrimitive);
            conflationDs.addPrimitive(way);
        }

        MainApplication.getLayerManager().addLayer(new OsmDataLayer(osm, "OSM Layer", null));
        GetDataRunnable.removeAlreadyAddedData(conflationDs);

        assertAll(() -> assertTrue(duplicateWay.isDeleted(), "The duplicate way should be deleted"),
                () -> duplicateWay.getNodes()
                        .forEach(node -> assertTrue(node.isDeleted(), "The duplicate way nodes should be deleted")),
                () -> assertFalse(nonDuplicateWay.isDeleted(), "The non-duplicate way should not be deleted"));
    }

    /**
     * Non-regression test for <a
     * href=https://gitlab.com/gokaart/JOSM_MapWithAI/-/issues/90>#90</a>
     */
    @Test
    public void testEmptyTagReplacement() {
        MapWithAIPreferenceHelper.setReplacementTags(Collections.singletonMap("", ""));
        DataSet ds = new DataSet();
        assertDoesNotThrow(() -> GetDataRunnable.replaceTags(ds));
    }
}
