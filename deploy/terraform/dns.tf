data "aws_acm_certificate" "wildcard" {
  domain   = "*.xevbn.click"
  statuses = ["ISSUED"]
}

resource "aws_route53_zone" "public" {
  name    = "xevbn.click"
  comment = "HostedZone created by Route53 Registrar"
}

resource "aws_route53_record" "api" {
  zone_id = aws_route53_zone.public.zone_id
  name    = "api.xevbn.click"
  type    = "A"

  alias {
    name                   = "dualstack.${aws_lb.main.dns_name}"
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = true
  }
}

resource "aws_route53_record" "grafana" {
  zone_id = aws_route53_zone.public.zone_id
  name    = "grafana.xevbn.click"
  type    = "A"

  alias {
    name                   = "dualstack.${aws_lb.main.dns_name}"
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = true
  }
}

# ACM *.xevbn.click DNS 검증 레코드. 지우면 인증서 자동 갱신이 실패한다.
resource "aws_route53_record" "acm_validation" {
  zone_id = aws_route53_zone.public.zone_id
  name    = "_23c36f8dce3fc762b9d440ece409d660.xevbn.click"
  type    = "CNAME"
  ttl     = 300
  records = ["_ffe4f6ac3395837b0484ebec348c5b9b.jkddzztszm.acm-validations.aws."]
}

resource "aws_route53_zone" "internal" {
  name    = "attraction.internal"
  comment = "KAN-127 internal service names"

  vpc {
    vpc_id = aws_vpc.main.id
  }
}

# Fluent Bit 사이드카가 로그를 보내는 주소. 모니터링 EC2의 고정 사설 IP를 가리킨다.
resource "aws_route53_record" "loki" {
  zone_id = aws_route53_zone.internal.zone_id
  name    = "loki.attraction.internal"
  type    = "A"
  ttl     = 60
  records = [aws_instance.monitoring.private_ip]
}
