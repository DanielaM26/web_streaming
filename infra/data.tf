data "aws_caller_identity" "current" {}

data "aws_ami" "amazon_linux_2023" {
  count       = var.enable_janus_instance ? 1 : 0
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}
