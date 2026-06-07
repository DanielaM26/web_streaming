resource "aws_iam_role" "janus_instance" {
  count = var.enable_janus_instance ? 1 : 0
  name  = "${local.name_prefix}-janus-instance"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ec2.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "janus_ssm" {
  count      = var.enable_janus_instance ? 1 : 0
  role       = aws_iam_role.janus_instance[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "janus" {
  count = var.enable_janus_instance ? 1 : 0
  name  = "${local.name_prefix}-janus"
  role  = aws_iam_role.janus_instance[0].name
}

resource "aws_instance" "janus" {
  count                       = var.enable_janus_instance ? 1 : 0
  ami                         = data.aws_ami.amazon_linux_2023[0].id
  instance_type               = var.instance_type
  vpc_security_group_ids      = [aws_security_group.janus[0].id]
  iam_instance_profile        = aws_iam_instance_profile.janus[0].name
  associate_public_ip_address = true
  user_data                   = file("${path.module}/scripts/install-janus.sh")

  root_block_device {
    volume_size           = 8
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
  }

  tags = {
    Name = "${local.name_prefix}-janus"
  }
}
