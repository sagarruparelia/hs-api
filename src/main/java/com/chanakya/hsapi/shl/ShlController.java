package com.chanakya.hsapi.shl;

import com.chanakya.hsapi.shl.dto.*;
import com.chanakya.hsapi.shl.service.ShlService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/secure/api/v1/shl")
public class ShlController {

    private final ShlService shlService;

    public ShlController(ShlService shlService) {
        this.shlService = shlService;
    }

    @PostMapping("/search")
    public ResponseEntity<List<ShlLinkResponse>> search(@RequestBody ShlSearchRequest request) {
        return ResponseEntity.ok(shlService.search(request));
    }

    @PostMapping("/get")
    public ResponseEntity<ShlLinkResponse> get(@RequestBody ShlSearchRequest request) {
        return ResponseEntity.ok(shlService.get(request));
    }

    @PostMapping(value = "/preview", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> preview(@RequestBody ShlSearchRequest request) {
        return ResponseEntity.ok(shlService.preview(request));
    }

    @PostMapping("/create")
    public ResponseEntity<ShlCreateResponse> create(@RequestBody ShlCreateRequest request,
                                                     HttpServletRequest httpRequest) {
        return ResponseEntity.ok(shlService.create(request, httpRequest));
    }

    @PostMapping("/revoke")
    public ResponseEntity<Void> revoke(@RequestBody ShlRevokeRequest request,
                                        HttpServletRequest httpRequest) {
        shlService.revoke(request, httpRequest);
        return ResponseEntity.ok().build();
    }
}
