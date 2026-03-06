package com.chanakya.hsapi.shl;

import com.chanakya.hsapi.shl.dto.ManifestResponse;
import com.chanakya.hsapi.shl.service.ShlRetrievalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/shl")
public class ShlPublicController {

    private final ShlRetrievalService retrievalService;

    public ShlPublicController(ShlRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<String> getDirectFile(@PathVariable String id,
                                                 @RequestParam(required = false) String recipient,
                                                 HttpServletRequest request) {
        if (recipient == null || recipient.isBlank()) {
            return ResponseEntity.badRequest()
                .body("{\"error\":\"bad_request\",\"message\":\"recipient query parameter is required\"}");
        }

        String jwe = retrievalService.retrieveSnapshot(id, recipient, request);
        if (jwe == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body("{\"error\":\"not_found\",\"message\":\"Link not found or no longer valid\"}");
        }

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/jose"))
            .body(jwe);
    }

    @PostMapping("/{id}")
    public ResponseEntity<?> postManifest(@PathVariable String id,
                                           @RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        Object recipientRaw = body != null ? body.get("recipient") : null;
        if (!(recipientRaw instanceof String recipient) || recipient.isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "bad_request", "message", "recipient field is required"));
        }

        ManifestResponse manifest = retrievalService.retrieveManifest(id, recipient, request);
        if (manifest == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "not_found", "message", "Link not found or no longer valid"));
        }
        if ("no-longer-valid".equals(manifest.status())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(manifest);
        }

        return ResponseEntity.ok(manifest);
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.OPTIONS)
    public ResponseEntity<Void> options(@PathVariable String id) {
        return ResponseEntity.ok()
            .header("Access-Control-Allow-Origin", "*")
            .header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            .header("Access-Control-Allow-Headers", "Content-Type")
            .header("Access-Control-Max-Age", "3600")
            .build();
    }
}
