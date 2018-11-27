package com.graphhopper.resources;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopperAPI;
import com.graphhopper.routing.util.HintsMap;
import com.graphhopper.util.Helper;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.StopWatch;
import com.graphhopper.util.shapes.GHPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.graphhopper.util.Parameters.Routing.*;

@Path("matrix")
public class MatrixRouteResource {

    private static final Logger logger = LoggerFactory.getLogger(RouteResource.class);

    private final GraphHopperAPI graphHopper;
    private final Boolean hasElevation;

    @Inject
    public MatrixRouteResource(GraphHopperAPI graphHopper, @Named("hasElevation") Boolean hasElevation) {
        this.graphHopper = graphHopper;
        this.hasElevation = hasElevation;
    }

    @GET
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, "application/gpx+xml"})
    public Response doGet(
            @Context HttpServletRequest httpReq,
            @Context UriInfo uriInfo,
            @Context ContainerRequestContext rc,
            @QueryParam(WAY_POINT_MAX_DISTANCE) @DefaultValue("1") double minPathPrecision,
            @QueryParam("point") List<GHPoint> requestPoints,
            @QueryParam("id") List<String> idsPoints,
            @QueryParam(CALC_POINTS) @DefaultValue("true") boolean calcPoints,
            @QueryParam("elevation") @DefaultValue("false") boolean enableElevation,
            @QueryParam("points_encoded") @DefaultValue("true") boolean pointsEncoded,
            @QueryParam("vehicle") @DefaultValue("car") String vehicleStr,
            @QueryParam("weighting") @DefaultValue("fastest") String weighting,
            @QueryParam("algorithm") @DefaultValue("") String algoStr,
            @QueryParam("locale") @DefaultValue("en") String localeStr,
            @QueryParam(Parameters.Routing.POINT_HINT) List<String> pointHints,
            @QueryParam(Parameters.DETAILS.PATH_DETAILS) List<String> pathDetails,
            @QueryParam("heading") List<Double> favoredHeadings,
            @QueryParam("gpx.route") @DefaultValue("true") boolean withRoute /* default to false for the route part in next API version, see #437 */,
            @QueryParam("gpx.track") @DefaultValue("true") boolean withTrack,
            @QueryParam("gpx.waypoints") @DefaultValue("false") boolean withWayPoints,
            @QueryParam("gpx.trackname") @DefaultValue("GraphHopper Track") String trackName,
            @QueryParam("gpx.millis") String timeString) {
        return doPost(httpReq, uriInfo, rc, minPathPrecision, requestPoints, idsPoints, calcPoints, enableElevation, pointsEncoded,
                vehicleStr, weighting, algoStr, localeStr, pointHints, pathDetails, favoredHeadings,
                withRoute, withTrack, withWayPoints, trackName, timeString);
    }


    @POST
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, "application/gpx+xml"})
    public Response doPost(
            @Context HttpServletRequest httpReq,
            @Context UriInfo uriInfo,
            @Context ContainerRequestContext rc,
            @FormParam(WAY_POINT_MAX_DISTANCE) @DefaultValue("1") double minPathPrecision,
            @FormParam("point") List<GHPoint> requestPoints,
            @FormParam("id") List<String> idsPoints,
            @FormParam(CALC_POINTS) @DefaultValue("true") boolean calcPoints,
            @FormParam("elevation") @DefaultValue("false") boolean enableElevation,
            @FormParam("points_encoded") @DefaultValue("true") boolean pointsEncoded,
            @FormParam("vehicle") @DefaultValue("car") String vehicleStr,
            @FormParam("weighting") @DefaultValue("fastest") String weighting,
            @FormParam("algorithm") @DefaultValue("") String algoStr,
            @FormParam("locale") @DefaultValue("en") String localeStr,
            @FormParam(Parameters.Routing.POINT_HINT) List<String> pointHints,
            @FormParam(Parameters.DETAILS.PATH_DETAILS) List<String> pathDetails,
            @FormParam("heading") List<Double> favoredHeadings,
            @FormParam("gpx.route") @DefaultValue("true") boolean withRoute /* default to false for the route part in next API version, see #437 */,
            @FormParam("gpx.track") @DefaultValue("true") boolean withTrack,
            @FormParam("gpx.waypoints") @DefaultValue("false") boolean withWayPoints,
            @FormParam("gpx.trackname") @DefaultValue("GraphHopper Track") String trackName,
            @FormParam("gpx.millis") String timeString) {


        StopWatch sw = new StopWatch().start();

        if (requestPoints.isEmpty())
            throw new IllegalArgumentException("You have to pass at least one point");
        if (enableElevation && !hasElevation)
            throw new IllegalArgumentException("Elevation not supported!");
        if (favoredHeadings.size() > 1 && favoredHeadings.size() != requestPoints.size())
            throw new IllegalArgumentException("The number of 'heading' parameters must be <= 1 "
                    + "or equal to the number of points (" + requestPoints.size() + ")");
        if (pointHints.size() > 0 && pointHints.size() != requestPoints.size())
            throw new IllegalArgumentException("If you pass " + POINT_HINT + ", you need to pass a hint for every point, empty hints will be ignored");

        MatrixRouteResponse matrixRouteResponse = new MatrixRouteResponse();
        int i = 0, j = 0;
        for (GHPoint point1 : requestPoints) {
            String idPoint1 = idsPoints.get(i);
            for (GHPoint point2 : requestPoints) {
                String idPoint2 = idsPoints.get(j);
                if (!idPoint1.equals(idPoint2)) {
                    List<GHPoint> requestPair = new ArrayList<>();
                    requestPair.add(point1);
                    requestPair.add(point2);
                    GHRequest request;
                    if (favoredHeadings.size() > 0) {
                        // if only one favored heading is specified take as start heading
                        if (favoredHeadings.size() == 1) {
                            List<Double> paddedHeadings = new ArrayList<>(Collections.nCopies(requestPair.size(), Double.NaN));
                            paddedHeadings.set(0, favoredHeadings.get(0));
                            request = new GHRequest(requestPair, paddedHeadings);
                        } else {
                            request = new GHRequest(requestPair, favoredHeadings);
                        }
                    } else {
                        request = new GHRequest(requestPair);
                    }

                    initHints(request.getHints(), uriInfo.getQueryParameters());
                    request.setVehicle(vehicleStr).
                            setWeighting(weighting).
                            setAlgorithm(algoStr).
                            setLocale(localeStr).
                            setPointHints(pointHints).
                            setPathDetails(pathDetails).
                            getHints().
                            put(CALC_POINTS, calcPoints).
                            put(INSTRUCTIONS, false).
                            put(WAY_POINT_MAX_DISTANCE, minPathPrecision);

                    GHResponse ghResponse = graphHopper.route(request);
                    if (!ghResponse.hasErrors()) {
                        matrixRouteResponse.getElements().add(new MatrixRouteElement(idPoint1, idPoint2, ghResponse.getBest().getDistance(), ghResponse.getBest().getTime()));
                    }
                }
                j++;
            }
            j = 0;
            i++;
        }

        // TODO: Request logging and timing should perhaps be done somewhere outside
        float took = sw.stop().getSeconds();
        logger.info("Consulta de matriz " + requestPoints.size() + "x" + requestPoints.size() + " levou " + took + " segundos.");
        return Response.ok(jsonObject(matrixRouteResponse, took)).
                        header("X-GH-Took", "" + Math.round(took * 1000)).
                        build();

    }

    public static ObjectNode jsonObject(MatrixRouteResponse mxRsp, float took) {
        ObjectNode json = JsonNodeFactory.instance.objectNode();

        // If you replace GraphHopper with your own brand name, this is fine.
        // Still it would be highly appreciated if you mention us in your about page!

        final ObjectNode info = json.putObject("info");
        info.putArray("copyrights")
                .add("GraphHopper")
                .add("OpenStreetMap contributors");
        info.put("took", Math.round(took * 1000));
        ArrayNode jsonPathList = json.putArray("matrixElements");
        for (MatrixRouteElement element : mxRsp.getElements()) {
            ObjectNode jsonPath = jsonPathList.addObject();
            jsonPath.put("idPoint1", element.getIdPoint1());
            jsonPath.put("idPoint2", element.getIdPoint2());
            jsonPath.put("distance", Helper.round(element.getDistance(), 3));
            jsonPath.put("time", element.getTime());
        }
        return json;
    }

    static void initHints(HintsMap m, MultivaluedMap<String, String> parameterMap) {
        for (Map.Entry<String, List<String>> e : parameterMap.entrySet()) {
            if (e.getValue().size() == 1) {
                m.put(e.getKey(), e.getValue().get(0));
            } else {
                // Do nothing.
                // TODO: this is dangerous: I can only silently swallow
                // the forbidden multiparameter. If I comment-in the line below,
                // I get an exception, because "point" regularly occurs
                // multiple times.
                // I think either unknown parameters (hints) should be allowed
                // to be multiparameters, too, or we shouldn't use them for
                // known parameters either, _or_ known parameters
                // must be filtered before they come to this code point,
                // _or_ we stop passing unknown parameters alltogether..
                //
                // throw new WebApplicationException(String.format("This query parameter (hint) is not allowed to occur multiple times: %s", e.getKey()));
            }
        }
    }

    public static class MatrixRouteResponse {

        private List<MatrixRouteElement> elements = new ArrayList<>();

        public List<MatrixRouteElement> getElements() {
            return elements;
        }

        public void setElements(List<MatrixRouteElement> elements) {
            this.elements = elements;
        }
    }

    public static class MatrixRouteElement {

        private String idPoint1;
        private String idPoint2;
        private Double distance;
        private Long time;

        public MatrixRouteElement(String idPoint1, String idPoint2, Double distance, Long time) {
            this.idPoint1 = idPoint1;
            this.idPoint2 = idPoint2;
            this.distance = distance;
            this.time = time;
        }

        public String getIdPoint1() {
            return idPoint1;
        }

        public void setIdPoint1(String idPoint1) {
            this.idPoint1 = idPoint1;
        }

        public String getIdPoint2() {
            return idPoint2;
        }

        public void setIdPoint2(String idPoint2) {
            this.idPoint2 = idPoint2;
        }

        public Double getDistance() {
            return distance;
        }

        public void setDistance(Double distance) {
            this.distance = distance;
        }

        public Long getTime() {
            return time;
        }

        public void setTime(Long time) {
            this.time = time;
        }
    }

}
