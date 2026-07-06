-- V1: 创建示例业务表。
-- 所有数据均为假数据，不包含真实个人信息。
-- query_audit_logs 不在此迁移中创建（见 V3），也不会进入 Data Copilot schema 白名单。

-- 客户表
CREATE TABLE IF NOT EXISTS customers (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(100)  NOT NULL,
    email        VARCHAR(150),
    phone        VARCHAR(30),
    -- 高敏字段：仅建表占位，示例数据不插入真实值，guardrails 阻断直接查询
    password     VARCHAR(255),
    token        VARCHAR(255),
    secret       VARCHAR(255),
    id_card      VARCHAR(30),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- 商品表
CREATE TABLE IF NOT EXISTS products (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(150) NOT NULL,
    category     VARCHAR(80),
    price        NUMERIC(12, 2) NOT NULL DEFAULT 0,
    stock        INTEGER       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- 订单表
CREATE TABLE IF NOT EXISTS orders (
    id              BIGSERIAL PRIMARY KEY,
    customer_id     BIGINT NOT NULL REFERENCES customers(id),
    status          VARCHAR(30) NOT NULL DEFAULT 'pending',
    total_amount    NUMERIC(14, 2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_orders_customer_id ON orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_orders_created_at ON orders(created_at);

-- 订单明细表
CREATE TABLE IF NOT EXISTS order_items (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT NOT NULL REFERENCES orders(id),
    product_id      BIGINT NOT NULL REFERENCES products(id),
    quantity        INTEGER NOT NULL DEFAULT 1,
    unit_price      NUMERIC(12, 2) NOT NULL DEFAULT 0,
    subtotal        NUMERIC(14, 2) NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items(order_id);

-- 退款表
CREATE TABLE IF NOT EXISTS refunds (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT NOT NULL REFERENCES orders(id),
    amount          NUMERIC(14, 2) NOT NULL DEFAULT 0,
    status          VARCHAR(30) NOT NULL DEFAULT 'pending',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 营销活动表
CREATE TABLE IF NOT EXISTS marketing_events (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(150) NOT NULL,
    channel         VARCHAR(80),
    start_date      DATE,
    end_date        DATE,
    budget          NUMERIC(14, 2) NOT NULL DEFAULT 0
);
