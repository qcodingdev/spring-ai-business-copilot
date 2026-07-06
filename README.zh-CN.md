# Spring AI Business Copilot

[English](README.md) | 简体中文

Spring AI Business Copilot 是一个面向个人开发者、中小团队和企业内部系统的 Java AI 业务智能助手套件。

它不是另一个 AI 框架，而是一组可以直接运行、学习、改造和接入真实业务的 Spring AI 应用模块。

## 它要做什么

项目第一版先做一个完整业务模块：

- Data Copilot：数据库查询助手

后续模块：

- Resume Copilot：简历筛选和面试题助手
- Support Copilot：智能客服助手
- Knowledge Copilot：企业知识库助手
- Report Copilot：周报和业务报表助手

## 通用平台能力

多个业务模块共享以下能力：

- Spring Boot 应用基础
- Spring AI 集成
- prompt 模板
- tool calling
- 工具调用审计
- guardrails 和安全检查
- 用户和角色边界
- 示例业务数据
- Docker Compose 一键启动
- 中英文文档

## 第一模块：Data Copilot

Data Copilot 让用户用自然语言查询业务数据，并获得安全、可解释的 SQL 查询结果。

示例问题：

- 上个月总销售额是多少？
- 哪些商品退款率最高？
- 本周新增用户有多少？
- 哪类客户的客单价最高？

安全目标：

- 只生成只读 SQL
- 拦截破坏性 SQL
- 执行前展示 SQL
- 记录查询审计日志
- 用业务语言解释查询结果

## 项目目标

做一个真正有业务参考价值的 Java AI 项目：

- 能直接运行
- 容易理解
- 容易扩展
- 默认安全
- 贴近中小团队和企业内部系统场景

