-- V18：扩充 Data Copilot 的虚构示例业务数据。
--
-- 数据覆盖 2025-07 至 2026-07，便于演示月度趋势、渠道活动、退款率、
-- 客户与商品排行。邮箱使用 example.com，手机号使用保留测试号段，
-- 敏感字段只写固定掩码，不包含真实个人信息。

INSERT INTO customers (
    id, name, email, phone, password, token, secret, id_card, created_at
)
SELECT
    1000 + sequence_no,
    '示例客户-' || lpad(sequence_no::text, 3, '0'),
    'sample-' || sequence_no || '@example.com',
    '1380000' || lpad(sequence_no::text, 4, '0'),
    '******',
    '******',
    '******',
    '******',
    TIMESTAMPTZ '2025-07-01 09:00:00+08'
        + ((sequence_no * 3) % 365) * INTERVAL '1 day'
FROM generate_series(1, 120) AS source(sequence_no)
ON CONFLICT (id) DO NOTHING;

INSERT INTO products (id, name, category, price, stock, created_at)
SELECT
    1000 + sequence_no,
    (ARRAY['云服务套餐', '智能终端', '办公外设', '数据服务', '技术支持'])[1 + ((sequence_no - 1) % 5)]
        || '-' || lpad(sequence_no::text, 2, '0'),
    (ARRAY['SaaS', '硬件', '配件', '数据产品', '服务'])[1 + ((sequence_no - 1) % 5)],
    (99 + sequence_no * 37)::numeric(12, 2),
    20 + ((sequence_no * 17) % 280),
    TIMESTAMPTZ '2025-06-15 09:00:00+08'
FROM generate_series(1, 30) AS source(sequence_no)
ON CONFLICT (id) DO NOTHING;

INSERT INTO orders (id, customer_id, status, total_amount, created_at)
SELECT
    1000 + sequence_no,
    1001 + ((sequence_no * 7) % 120),
    CASE
        WHEN sequence_no % 20 < 14 THEN 'completed'
        WHEN sequence_no % 20 < 17 THEN 'shipped'
        WHEN sequence_no % 20 < 19 THEN 'pending'
        ELSE 'cancelled'
    END,
    (120 + ((sequence_no * 97) % 8800))::numeric(14, 2),
    TIMESTAMPTZ '2025-07-01 08:00:00+08'
        + ((sequence_no * 11) % 380) * INTERVAL '1 day'
        + (sequence_no % 12) * INTERVAL '1 hour'
FROM generate_series(1, 720) AS source(sequence_no)
ON CONFLICT (id) DO NOTHING;

INSERT INTO order_items (id, order_id, product_id, quantity, unit_price, subtotal)
SELECT
    1000 + sequence_no,
    1000 + sequence_no,
    1001 + ((sequence_no * 13) % 30),
    1,
    (120 + ((sequence_no * 97) % 8800))::numeric(12, 2),
    (120 + ((sequence_no * 97) % 8800))::numeric(14, 2)
FROM generate_series(1, 720) AS source(sequence_no)
ON CONFLICT (id) DO NOTHING;

INSERT INTO refunds (id, order_id, amount, status, created_at)
SELECT
    1000 + sequence_no,
    1000 + sequence_no,
    round((120 + ((sequence_no * 97) % 8800)) * 0.8, 2)::numeric(14, 2),
    CASE WHEN sequence_no % 3 = 0 THEN 'pending' ELSE 'completed' END,
    TIMESTAMPTZ '2025-07-03 10:00:00+08'
        + ((sequence_no * 11) % 380) * INTERVAL '1 day'
FROM generate_series(20, 720, 20) AS source(sequence_no)
ON CONFLICT (id) DO NOTHING;

INSERT INTO marketing_events (id, name, channel, start_date, end_date, budget)
SELECT
    1000 + sequence_no,
    to_char(DATE '2025-07-01' + (sequence_no - 1) * INTERVAL '1 month', 'YYYY-MM')
        || ' 示例增长活动',
    (ARRAY['搜索广告', '内容营销', '合作渠道', '客户转介绍'])[1 + ((sequence_no - 1) % 4)],
    (DATE '2025-07-01' + (sequence_no - 1) * INTERVAL '1 month')::date,
    (DATE '2025-07-01' + (sequence_no - 1) * INTERVAL '1 month' + INTERVAL '20 days')::date,
    (18000 + sequence_no * 2500)::numeric(14, 2)
FROM generate_series(1, 13) AS source(sequence_no)
ON CONFLICT (id) DO NOTHING;

SELECT setval('customers_id_seq', GREATEST((SELECT MAX(id) FROM customers), 1), true);
SELECT setval('products_id_seq', GREATEST((SELECT MAX(id) FROM products), 1), true);
SELECT setval('orders_id_seq', GREATEST((SELECT MAX(id) FROM orders), 1), true);
SELECT setval('order_items_id_seq', GREATEST((SELECT MAX(id) FROM order_items), 1), true);
SELECT setval('refunds_id_seq', GREATEST((SELECT MAX(id) FROM refunds), 1), true);
SELECT setval('marketing_events_id_seq', GREATEST((SELECT MAX(id) FROM marketing_events), 1), true);
