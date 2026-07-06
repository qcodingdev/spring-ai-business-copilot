# Spring AI Business Copilot

English | [简体中文](README.zh-CN.md)

Spring AI Business Copilot is an open-source Java AI business application suite for individuals, small teams, and internal enterprise systems.

It provides ready-to-run business modules such as data query, resume screening, customer support, knowledge assistant, and report generation. The goal is not to provide another AI framework, but to provide practical Spring AI applications that teams can clone, run, learn from, and adapt to real business systems.

## What It Builds

The project will start with one complete module:

- Data Copilot: natural-language database query assistant

Future modules:

- Resume Copilot: resume screening and interview question assistant
- Support Copilot: customer support assistant
- Knowledge Copilot: enterprise knowledge base assistant
- Report Copilot: weekly report and business report assistant

## Core Platform Capabilities

Shared capabilities across modules:

- Spring Boot application foundation
- Spring AI integration
- prompt templates
- tool calling
- tool call audit
- guardrails and safety checks
- user and role boundaries
- sample business data
- Docker Compose startup
- bilingual documentation

## First Module: Data Copilot

Data Copilot helps users ask business questions in natural language and get safe, explainable SQL query results.

Example questions:

- What was last month's total revenue?
- Which products had the highest refund rate?
- How many new users registered this week?
- Which customer segment has the highest average order value?

Safety goals:

- generate read-only SQL
- block destructive SQL
- show SQL before execution
- record query audit logs
- explain query results in business language

## Project Goal

Build a practical Java AI business project that is useful beyond demos:

- easy to run
- easy to understand
- easy to extend
- safe by default
- close to real small-team and enterprise scenarios

