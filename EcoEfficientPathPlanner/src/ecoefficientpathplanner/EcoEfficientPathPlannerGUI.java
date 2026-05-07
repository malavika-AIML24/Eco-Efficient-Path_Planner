package ecoefficientpathplanner;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class EcoEfficientPathPlannerGUI extends Application {

    private static final String ORS_API_KEY = System.getenv("ORS_API_KEY");
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    private WebEngine webEngine;
    private Label metricsLabel;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        if (ORS_API_KEY == null || ORS_API_KEY.isEmpty()) {
            throw new IllegalStateException("ORS_API_KEY environment variable is not set.");
        }

        WebView webView = new WebView();
        webEngine = webView.getEngine();
        webEngine.load(getClass().getResource("map.html").toExternalForm());

        TextField startField = new TextField();
        startField.setPromptText("Enter start city");
        TextField endField = new TextField();
        endField.setPromptText("Enter destination city");
        Button goButton = new Button("Find Eco Route");
        metricsLabel = new Label("Metrics will appear here");

        HBox controls = new HBox(10, new Label("Start:"), startField, new Label("End:"), endField, goButton);
        VBox root = new VBox(10, controls, metricsLabel, webView);
        VBox.setVgrow(webView, Priority.ALWAYS);

        goButton.setOnAction(e -> {
            String start = startField.getText();
            String end = endField.getText();
            try {
                findRoute(start, end);
            } catch (Exception ex) {
                metricsLabel.setText("Error: " + ex.getMessage());
                ex.printStackTrace();
            }
        });

        primaryStage.setScene(new Scene(root, 1000, 700));
        primaryStage.setTitle("Eco-Efficient Path Planner (JavaFX)");
        primaryStage.show();
    }

    private void findRoute(String start, String end) throws IOException, InterruptedException {
        double[] startCoords = geocode(start);
        double[] endCoords = geocode(end);

        if (startCoords == null || endCoords == null) {
            metricsLabel.setText("Could not find coordinates for start or end.");
            return;
        }

        JsonNode routeData = getRouteAndMetrics(startCoords, endCoords);

        if (routeData.has("error")) {
            metricsLabel.setText("Error: " + routeData.get("error").asText());
            return;
        }

        metricsLabel.setText(String.format(
                "Distance: %.2f km  Duration: %.2f min  Fuel: %.2f L  CO₂: %.2f g",
                routeData.get("distance_km").asDouble(),
                routeData.get("duration_min").asDouble(),
                routeData.get("fuel_used_l").asDouble(),
                routeData.get("co2_g").asDouble()
        ));

        String geojsonString = mapper.writeValueAsString(routeData.get("geojson"));
        webEngine.executeScript("drawRoute(" + geojsonString + ")");
    }

    private double[] geocode(String location) throws IOException, InterruptedException {
        String q = URLEncoder.encode(location, StandardCharsets.UTF_8);
        String url = "https://nominatim.openstreetmap.org/search?q=" + q + "&format=json&limit=1";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "EcoEfficientPathPlannerJavaFX/1.0")
                .GET()
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode arr = mapper.readTree(res.body());
        if (arr.isArray() && arr.size() > 0) {
            double lat = Double.parseDouble(arr.get(0).get("lat").asText());
            double lon = Double.parseDouble(arr.get(0).get("lon").asText());
            return new double[]{lat, lon};
        } else return null;
    }

    private JsonNode getRouteAndMetrics(double[] start, double[] end) throws IOException, InterruptedException {
        String url = "https://api.openrouteservice.org/v2/directions/driving-car/geojson";
        String body = String.format("{\"coordinates\":[[%f,%f],[%f,%f]]}",
                start[1], start[0], end[1], end[0]);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", ORS_API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() >= 400) {
            return mapper.createObjectNode().put("error", "ORS request failed with status " + res.statusCode());
        }

        JsonNode geojson = mapper.readTree(res.body());
        JsonNode segs = geojson.path("features").get(0).path("properties").path("segments");
        double distanceMeters = 0;
        double durationSeconds = 0;
        if (segs.isArray() && segs.size() > 0) {
            distanceMeters = segs.get(0).path("distance").asDouble(0);
            durationSeconds = segs.get(0).path("duration").asDouble(0);
        }

        double distanceKm = Math.round((distanceMeters / 1000.0) * 100.0) / 100.0;
        double durationMin = Math.round((durationSeconds / 60.0) * 100.0) / 100.0;
        double fuelUsed = Math.round((distanceKm / 18.0) * 100.0) / 100.0;
        double co2 = Math.round(fuelUsed * 2310 * 100.0) / 100.0;

        JsonNode root = mapper.createObjectNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).set("geojson", geojson);
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("distance_km", distanceKm);
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("duration_min", durationMin);
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("fuel_used_l", fuelUsed);
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("co2_g", co2);

        return root;
    }
}