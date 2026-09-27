package org.heatlab.atlas;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ProjectDataController {
    private final ProjectDataService dataService;

    public ProjectDataController(ProjectDataService dataService) {
        this.dataService = dataService;
    }

    @GetMapping("/overview")
    public ProjectDataService.Overview overview() {
        return dataService.overview();
    }

    @GetMapping("/datasets")
    public List<ProjectDataService.DatasetSummary> datasets() {
        return dataService.datasets();
    }

    @GetMapping("/datasets/{id}/series")
    public ProjectDataService.SeriesResponse series(
            @PathVariable String id,
            @RequestParam(required = false) String field,
            @RequestParam(defaultValue = "240") int limit) {
        return dataService.series(id, field, limit);
    }

    @GetMapping("/datasets/{id}/csv")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        Path path = dataService.csvPath(id);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(path.getFileName().toString()).build().toString())
                .body(new FileSystemResource(path));
    }
}