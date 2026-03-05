#!/bin/bash
awslocal sqs create-queue --queue-name fieldiq-agent-tasks
awslocal sqs create-queue --queue-name fieldiq-notifications
awslocal sqs create-queue --queue-name fieldiq-negotiation
