package com.example.attractionreservation.domain.ticket.controller;

import com.example.attractionreservation.domain.ticket.dto.SearchTicketCondition;
import com.example.attractionreservation.domain.ticket.dto.SearchTicketDetailResponse;
import com.example.attractionreservation.domain.ticket.dto.SearchTicketListResponse;
import com.example.attractionreservation.domain.ticket.dto.SearchTicketResponse;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;


@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    // 내 티켓 목록 조회 (이름, 종류, 상태, 페이징)
    // TODO: 추후 인증 완료 후 인증 검사
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<SearchTicketListResponse>> getTicketAll(
            @ModelAttribute SearchTicketCondition condition,
            @PageableDefault(page = 0, size = 10, sort = "status", direction = Sort.Direction.DESC) Pageable pageable

    ) {
        Sort sort = pageable.getSort();

        if (sort.getOrderFor("id") == null) {
            sort = sort.and(Sort.by(Sort.Direction.DESC, "id"));
        }

        Long userId = 1L; // 인증 전 임시 id
        Pageable stablePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);

        Page<SearchTicketResponse> result = ticketService.findTicketAll(userId, condition, stablePageable);
        SearchTicketListResponse response = SearchTicketListResponse.from(result);

        return ResponseEntity.ok(ApiResponse.ok("내 티켓 목록 조회에 성공했습니다.", response));
    }

    // 내 티켓 상세 조회
    // TODO : 추후 인증 완료 후 인증 검사
    @GetMapping("/{ticketId}")
    public ResponseEntity<ApiResponse<SearchTicketDetailResponse>> getTicketById(
            @PathVariable Long ticketId
    ) {
        SearchTicketDetailResponse response = ticketService.findTicketById(ticketId);
        return ResponseEntity.ok(ApiResponse.ok("티켓 상세 조회에 성공했습니다.", response));
    }
}
