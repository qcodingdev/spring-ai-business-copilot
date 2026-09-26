-- V41：定时报告恢复创建或更新时的界面语言；历史调度保持默认中文。

ALTER TABLE report_schedules
    ADD COLUMN locale VARCHAR(10) NOT NULL DEFAULT 'zh-CN';

ALTER TABLE report_schedules
    ADD CONSTRAINT ck_report_schedule_locale CHECK (locale IN ('zh-CN', 'en-US'));
