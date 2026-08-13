package com.interviewagent.material;

import static com.interviewagent.material.MaterialRequests.*;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1")
public class MaterialController {
    private final MaterialService service;
    private final ResumeFileService resumeFiles;
    public MaterialController(MaterialService service, ResumeFileService resumeFiles) { this.service = service; this.resumeFiles = resumeFiles; }

    @GetMapping("/resumes") List<Resume> resumes(@AuthenticationPrincipal Jwt jwt) { return service.resumes(jwt.getSubject()); }
    @GetMapping("/resumes/{id}") Resume resume(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.resume(jwt.getSubject(), id); }
    @PostMapping("/resumes") @ResponseStatus(HttpStatus.CREATED) Resume createResume(@AuthenticationPrincipal Jwt jwt, @RequestBody ResumeRequest request) { return service.createResume(jwt.getSubject(), request); }
    @PutMapping("/resumes/{id}") Resume updateResume(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody ResumeRequest request) { return service.updateResume(jwt.getSubject(), id, request); }
    @DeleteMapping("/resumes/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteResume(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { service.deleteResume(jwt.getSubject(), id); }

    @GetMapping("/resume-files") List<ResumeFile> resumeFiles(@AuthenticationPrincipal Jwt jwt) { return resumeFiles.files(jwt.getSubject()); }
    @PostMapping(path = "/resume-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED) ResumeFile uploadResumeFile(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file) { return resumeFiles.upload(jwt.getSubject(), file); }
    @GetMapping("/resume-files/{id}") ResumeFile resumeFile(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return resumeFiles.metadata(jwt.getSubject(), id); }
    @GetMapping("/resume-files/{id}/content") ResponseEntity<byte[]> resumeFileContent(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        ResumeFile file = resumeFiles.metadata(jwt.getSubject(), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType())).contentLength(file.sizeBytes())
            .cacheControl(CacheControl.noStore()).header("Content-Disposition", ContentDisposition.attachment().filename(file.originalFilename(), StandardCharsets.UTF_8).build().toString())
            .body(resumeFiles.content(jwt.getSubject(), id));
    }
    @DeleteMapping("/resume-files/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteResumeFile(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { resumeFiles.delete(jwt.getSubject(), id); }

    @GetMapping("/job-descriptions") List<JobDescription> jobDescriptions(@AuthenticationPrincipal Jwt jwt) { return service.jobDescriptions(jwt.getSubject()); }
    @GetMapping("/job-descriptions/{id}") JobDescription jobDescription(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.jobDescription(jwt.getSubject(), id); }
    @PostMapping("/job-descriptions") @ResponseStatus(HttpStatus.CREATED) JobDescription createJobDescription(@AuthenticationPrincipal Jwt jwt, @RequestBody JobDescriptionRequest request) { return service.createJobDescription(jwt.getSubject(), request); }
    @PutMapping("/job-descriptions/{id}") JobDescription updateJobDescription(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody JobDescriptionRequest request) { return service.updateJobDescription(jwt.getSubject(), id, request); }
    @DeleteMapping("/job-descriptions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteJobDescription(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { service.deleteJobDescription(jwt.getSubject(), id); }

    @GetMapping("/evidence-cards") List<ProjectEvidenceCard> evidenceCards(@AuthenticationPrincipal Jwt jwt) { return service.evidenceCards(jwt.getSubject()); }
    @GetMapping("/evidence-cards/{id}") ProjectEvidenceCard evidenceCard(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.evidenceCard(jwt.getSubject(), id); }
    @PostMapping("/evidence-cards") @ResponseStatus(HttpStatus.CREATED) ProjectEvidenceCard createEvidenceCard(@AuthenticationPrincipal Jwt jwt, @RequestBody EvidenceCardRequest request) { return service.createEvidenceCard(jwt.getSubject(), request); }
    @PutMapping("/evidence-cards/{id}") ProjectEvidenceCard updateEvidenceCard(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody EvidenceCardRequest request) { return service.updateEvidenceCard(jwt.getSubject(), id, request); }
    @DeleteMapping("/evidence-cards/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteEvidenceCard(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { service.deleteEvidenceCard(jwt.getSubject(), id); }

    @GetMapping("/interview-packages") List<InterviewPackage> interviewPackages(@AuthenticationPrincipal Jwt jwt) { return service.interviewPackages(jwt.getSubject()); }
    @GetMapping("/interview-packages/{id}") InterviewPackage interviewPackage(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return service.interviewPackage(jwt.getSubject(), id); }
    @PostMapping("/interview-packages") @ResponseStatus(HttpStatus.CREATED) InterviewPackage createInterviewPackage(@AuthenticationPrincipal Jwt jwt, @RequestBody InterviewPackageRequest request) { return service.createInterviewPackage(jwt.getSubject(), request); }
    @PutMapping("/interview-packages/{id}") InterviewPackage updateInterviewPackage(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody InterviewPackageRequest request) { return service.updateInterviewPackage(jwt.getSubject(), id, request); }
    @DeleteMapping("/interview-packages/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteInterviewPackage(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { service.deleteInterviewPackage(jwt.getSubject(), id); }
}
