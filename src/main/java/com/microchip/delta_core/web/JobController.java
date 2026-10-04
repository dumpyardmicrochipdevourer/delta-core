package com.microchip.delta_core.web;

import com.microchip.delta_core.domain.Job;
import com.microchip.delta_core.domain.Mode;
import com.microchip.delta_core.domain.Options;
import com.microchip.delta_core.domain.Owner;
import com.microchip.delta_core.service.JobService;
import com.microchip.delta_core.service.ProbeService;
import com.microchip.delta_core.service.Refusal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class JobController {

    public record CreateJobRequest(String url, String mode, Options options, List<Integer> items) {
    }

    public record ProbeRequest(String url) {
    }

    public record LimitsView(boolean signedIn, long maxFileBytes, int playlistItems) {
    }

    private final JobService jobs;
    private final ProbeService probes;
    private final Owners owners;

    public JobController(JobService jobs, ProbeService probes, Owners owners) {
        this.jobs = jobs;
        this.probes = probes;
        this.owners = owners;
    }

    @GetMapping("/limits")
    public LimitsView limits(HttpServletRequest request, @AuthenticationPrincipal Jwt jwt) {
        Owner owner = owners.of(request, jwt);
        JobService.Tier tier = jobs.tier(owner);
        return new LimitsView(owner.kind() != Owner.Kind.ANON, tier.maxFileBytes(), tier.playlistItems());
    }

    @PostMapping("/probe")
    public ProbeService.Probe probe(@RequestBody ProbeRequest body) throws IOException, InterruptedException {
        return probes.probe(body.url());
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    public JobView create(HttpServletRequest request, @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateJobRequest body) {
        Job job = jobs.create(owners.of(request, jwt), body.url(), mode(body.mode()), body.options(), body.items());
        return JobView.of(job, jobs.position(job), owners.isBot(request));
    }

    @GetMapping("/jobs/{id}")
    public JobView get(HttpServletRequest request, @PathVariable String id) {
        Job job = jobs.get(id);
        return JobView.of(job, jobs.position(job), owners.isBot(request));
    }

    @DeleteMapping("/jobs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@PathVariable String id) {
        jobs.discard(jobs.get(id));
    }

    @GetMapping("/jobs/{id}/file")
    public ResponseEntity<ResourceRegion> file(@PathVariable String id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            HttpServletResponse response) throws IOException {
        Job job = jobs.get(id);
        Path file = jobs.file(job);
        Job.Result result = job.getResult();
        String disposition = ContentDisposition.attachment().filename(result.fileName(), StandardCharsets.UTF_8)
                .build().toString();

        if (result.archive()) {
            // zipped on the way out: a copy on disk would not fit into the budget next to the files
            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition);
            try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream())) {
                zip.setLevel(Deflater.NO_COMPRESSION);
                for (String name : result.files()) {
                    Path p = Path.of(name);
                    zip.putNextEntry(new ZipEntry(p.getFileName().toString()));
                    Files.copy(p, zip);
                    zip.closeEntry();
                }
            }
            return null;
        }

        FileSystemResource resource = new FileSystemResource(file);
        ResourceRegion region = range != null
                ? HttpRange.parseRanges(range).get(0).toResourceRegion(resource)
                : new ResourceRegion(resource, 0, result.sizeBytes());
        return ResponseEntity.status(range != null ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_TYPE, MediaTypeFactory.getMediaType(resource)
                        .orElse(MediaType.APPLICATION_OCTET_STREAM).toString())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header("X-Content-Type-Options", "nosniff")
                .body(region);
    }

    @GetMapping("/jobs/{id}/thumb")
    public ResponseEntity<FileSystemResource> thumb(@PathVariable String id) {
        Job.Result result = jobs.get(id).getResult();
        if (result == null || result.thumb() == null) {
            throw new Refusal(404, "not_found", "превью нет");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(new FileSystemResource(Path.of(result.thumb())));
    }

    private static Mode mode(String value) {
        if (value == null) {
            return Mode.VIDEO;
        }
        try {
            return Mode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("режим должен быть video, audio или mute");
        }
    }

    @ExceptionHandler(Refusal.class)
    ResponseEntity<ProblemDetail> handleRefusal(Refusal e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
        problem.setProperties(Map.of("code", e.code()));
        return ResponseEntity.status(e.status()).body(problem);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ProblemDetail handleBadRequest(Exception e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                e instanceof IllegalArgumentException ? e.getMessage() : "некорректный запрос");
        problem.setProperties(Map.of("code", "bad_request"));
        return problem;
    }
}
