resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "my-vpc"
  }
}

resource "aws_subnet" "public_2a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.0.0/20"
  availability_zone = "ap-northeast-2a"

  tags = {
    Name = "my-subnet-public1-ap-northeast-2a"
  }
}

resource "aws_subnet" "public_2b" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.16.0/20"
  availability_zone = "ap-northeast-2b"

  tags = {
    Name = "my-subnet-public2-ap-northeast-2b"
  }
}

resource "aws_subnet" "private_2a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.128.0/20"
  availability_zone = "ap-northeast-2a"

  tags = {
    Name = "my-subnet-private1-ap-northeast-2a"
  }
}

resource "aws_subnet" "private_2b" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.144.0/20"
  availability_zone = "ap-northeast-2b"

  tags = {
    Name = "my-subnet-private2-ap-northeast-2b"
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "my-igw"
  }
}

# 비용 때문에 NAT는 2a에 하나만 둔다. private 라우팅 테이블 두 개가 모두 이 NAT를 쓴다.
resource "aws_eip" "nat_2a" {
  domain = "vpc"

  tags = {
    Name = "attraction-final-nat-2a"
  }
}

resource "aws_nat_gateway" "nat_2a" {
  allocation_id = aws_eip.nat_2a.id
  subnet_id     = aws_subnet.public_2a.id

  tags = {
    Name = "attraction-final-nat-2a"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "my-rtb-public"
  }
}

resource "aws_route_table" "private_2a" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.nat_2a.id
  }

  tags = {
    Name = "my-rtb-private1-ap-northeast-2a"
  }
}

resource "aws_route_table" "private_2b" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.nat_2a.id
  }

  tags = {
    Name = "my-rtb-private2-ap-northeast-2b"
  }
}

resource "aws_route_table_association" "public_2a" {
  subnet_id      = aws_subnet.public_2a.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "public_2b" {
  subnet_id      = aws_subnet.public_2b.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private_2a" {
  subnet_id      = aws_subnet.private_2a.id
  route_table_id = aws_route_table.private_2a.id
}

resource "aws_route_table_association" "private_2b" {
  subnet_id      = aws_subnet.private_2b.id
  route_table_id = aws_route_table.private_2b.id
}

# S3 트래픽이 NAT를 거치지 않게 하는 게이트웨이 엔드포인트
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${local.region}.s3"
  vpc_endpoint_type = "Gateway"

  tags = {
    Name = "attraction-final-s3"
  }
}

resource "aws_vpc_endpoint_route_table_association" "s3_private_2a" {
  vpc_endpoint_id = aws_vpc_endpoint.s3.id
  route_table_id  = aws_route_table.private_2a.id
}

resource "aws_vpc_endpoint_route_table_association" "s3_private_2b" {
  vpc_endpoint_id = aws_vpc_endpoint.s3.id
  route_table_id  = aws_route_table.private_2b.id
}
