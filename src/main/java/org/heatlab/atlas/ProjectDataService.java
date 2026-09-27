package org.heatlab.atlas;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectDataService {
    private static final List<DatasetDefinition> DEFINITIONS = List.of(
            new DatasetDefinition("building-measurements", "Outdoor temperature", "Building",
                    "Measured outdoor air temperature for the university building.",
                    "MPC building heating system/Measurement data.csv", "T_meas", "deg C"),
            new DatasetDefinition("building-forecast", "Weather forecast", "Building",
                    "Forecast outdoor air temperature used by the building MPC study.",
                    "MPC building heating system/Forecast data.csv", "T_fore", "deg C"),
            new DatasetDefinition("building-price", "Heat price and demand", "Building",
                    "Energy demand price signal used in the building control data.",
                    "MPC building heating system/Heat price energy demand.csv", "P_EDC", "NOK/kWh"),
            new DatasetDefinition("building-reference", "Indoor temperature reference", "Building",
                    "Indoor air temperature reference signal for the building study.",
                    "MPC building heating system/Indoor air temperature reference.csv", "Tiaref", "deg C"),
            new DatasetDefinition("building-gains", "Internal heat gains", "Building",
                    "Internal heat gain time series supplied to the building model.",
                    "MPC building heating system/Internal heat gain.csv", "Q_in", "W"),
            new DatasetDefinition("building-ventilation", "Ventilation heat transfer", "Building",
                    "Mechanical ventilation heat transfer coefficient time series.",
                    "MPC building heating system/Mechanical ventilation heat transfer coefficient.csv", "Hv", "W/K"),
            new DatasetDefinition("district-mpc", "District MPC inputs", "District",
                    "Hourly weather, heat demand, and energy price inputs for district MPC.",
                    "MPC DH system/Inputs.csv", "qload", "W"),
            new DatasetDefinition("district-optimization", "District optimization inputs", "District",
                    "Year-level input data for district heating system optimization.",
                    "Optimization DH system/Inputs.csv", "qdhw", "W")
    );

    private final Path dataRoot;

    public ProjectDataService(@Value("${heating.data-root:.}") String dataRoot) {
        this.dataRoot = Path.of(dataRoot).toAbsolutePath().normalize();
    }

    public Overview overview() {
        List<DatasetSummary> datasets = datasets();
        int buildingRecords = datasets.stream()
                .filter(dataset -> dataset.category().equals("Building"))
                .mapToInt(DatasetSummary::records).sum();
        int districtRecords = datasets.stream()
                .filter(dataset -> dataset.category().equals("District"))
                .mapToInt(DatasetSummary::records).sum();
        int totalRecords = datasets.stream().mapToInt(DatasetSummary::records).sum();
        return new Overview(datasets.size(), totalRecords, buildingRecords, districtRecords);
    }

    public List<DatasetSummary> datasets() {
        return DEFINITIONS.stream().map(definition -> {
            LoadedDataset data = load(definition);
            return new DatasetSummary(definition.id(), definition.name(), definition.category(),
                    definition.description(), data.rows().size(), data.numericFields(),
                    definition.defaultField(), definition.unit());
        }).toList();
    }

    public DatasetSummary dataset(String id) {
        LoadedDataset data = load(definition(id));
        DatasetDefinition definition = data.definition();
        return new DatasetSummary(definition.id(), definition.name(), definition.category(),
                definition.description(), data.rows().size(), data.numericFields(),
                definition.defaultField(), definition.unit());
    }

    public SeriesResponse series(String id, String requestedField, int requestedLimit) {
        LoadedDataset data = load(definition(id));
        String field = requestedField == null || requestedField.isBlank()
                ? data.definition().defaultField() : requestedField;
        Integer valueIndex = data.columnIndexes().get(field);
        Integer timeIndex = data.columnIndexes().get("Time");
        if (valueIndex == null || timeIndex == null || !data.numericFields().contains(field)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown numeric field: " + field);
        }

        int limit = Math.max(20, Math.min(requestedLimit, 1000));
        List<Point> points = new ArrayList<>();
        int rowCount = data.rows().size();
        int pointCount = Math.min(rowCount, limit);
        for (int point = 0; point < pointCount; point++) {
            int rowIndex = pointCount == 1 ? 0 : (int) Math.round(point * (rowCount - 1.0) / (pointCount - 1));
            List<String> row = data.rows().get(rowIndex);
            try {
                points.add(new Point(Double.parseDouble(row.get(timeIndex)),
                        Double.parseDouble(row.get(valueIndex))));
            } catch (NumberFormatException ignored) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "A non-numeric value was found in the selected series.");
            }
        }

        return new SeriesResponse(id, data.definition().name(), field, data.definition().unit(), points);
    }

    public Path csvPath(String id) {
        DatasetDefinition definition = definition(id);
        Path path = dataRoot.resolve(definition.relativePath()).normalize();
        if (!path.startsWith(dataRoot) || !Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset file is not available.");
        }
        return path;
    }

    private DatasetDefinition definition(String id) {
        return DEFINITIONS.stream().filter(definition -> definition.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset not found."));
    }

    private LoadedDataset load(DatasetDefinition definition) {
        Path path = csvPath(definition.id());
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.parse(reader)) {
            List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Dataset is empty.");
            }

            List<String> headers = new ArrayList<>();
            Map<String, Integer> indexes = new LinkedHashMap<>();
            CSVRecord header = records.get(0);
            for (int column = 0; column < header.size(); column++) {
                String name = header.get(column).replace("\uFEFF", "").trim();
                if (!name.isEmpty()) {
                    headers.add(name);
                    indexes.put(name, column);
                }
            }

            List<List<String>> rows = new ArrayList<>();
            for (int rowIndex = 1; rowIndex < records.size(); rowIndex++) {
                CSVRecord record = records.get(rowIndex);
                List<String> row = new ArrayList<>(headers.size());
                for (String headerName : headers) {
                    int column = indexes.get(headerName);
                    row.add(column < record.size() ? record.get(column).trim() : "");
                }
                rows.add(row);
            }

            List<String> numericFields = headers.stream()
                    .filter(name -> !name.equalsIgnoreCase("Time"))
                    .filter(name -> isNumericColumn(rows, indexes.get(name)))
                    .toList();
            if (numericFields.isEmpty() || !indexes.containsKey("Time")) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Dataset must contain a Time column and numeric measurements.");
            }
            return new LoadedDataset(definition, rows, indexes, numericFields);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not read dataset: " + definition.name(), exception);
        }
    }

    private boolean isNumericColumn(List<List<String>> rows, int column) {
        boolean foundValue = false;
        for (List<String> row : rows) {
            String value = row.get(column);
            if (value.isBlank()) {
                continue;
            }
            try {
                Double.parseDouble(value);
                foundValue = true;
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return foundValue;
    }

    public record Overview(int datasets, int totalRecords, int buildingRecords, int districtRecords) { }

    public record DatasetSummary(String id, String name, String category, String description,
                                 int records, List<String> fields, String defaultField, String unit) { }

    public record Point(double time, double value) { }

    public record SeriesResponse(String datasetId, String datasetName, String field,
                                 String unit, List<Point> points) { }

    private record DatasetDefinition(String id, String name, String category, String description,
                                     String relativePath, String defaultField, String unit) { }

    private record LoadedDataset(DatasetDefinition definition, List<List<String>> rows,
                                 Map<String, Integer> columnIndexes, List<String> numericFields) { }
}