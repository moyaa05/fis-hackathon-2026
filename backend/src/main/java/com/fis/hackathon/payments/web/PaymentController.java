package com.fis.hackathon.payments.web;

import com.fis.hackathon.api.dto.ApiDtos.AuthorizeRotationRequest;
import com.fis.hackathon.api.dto.ApiDtos.CreatePaymentRequest;
import com.fis.hackathon.api.dto.ApiDtos.PaymentView;
import com.fis.hackathon.api.dto.ApiDtos.SimSignRequest;
import com.fis.hackathon.payments.service.PaymentService;
import com.fis.hackathon.shared.SimSignature;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/api/payments")
    public ResponseEntity<PaymentView> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreatePaymentRequest request
    ) {
        var res = paymentService.create(request, idempotencyKey);
        return ResponseEntity.status(res.created() ? HttpStatus.CREATED : HttpStatus.OK).body(res.view());
    }

    @PostMapping("/api/payments/{id}/rotation-authorization")
    public PaymentView authorize(@PathVariable long id, @RequestBody AuthorizeRotationRequest request) {
        return paymentService.authorizeRotation(id, request);
    }

    @PostMapping("/api/sim/sign")
    public SimSignature simSign(@RequestBody SimSignRequest request) {
        return paymentService.simulateSign(request.paymentId(), request.as());
    }
}
