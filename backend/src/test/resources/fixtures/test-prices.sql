-- TEST-ONLY prices. Made-up round numbers, not real provider prices (real seed prices arrive in milestone 8).
-- The 2020 gpt-4o row exists to prove that the latest row in effect wins.
INSERT INTO model_price (provider, model, effective_from, input_per_mtok, output_per_mtok, cache_read_per_mtok, cache_write_per_mtok, source)
VALUES
  ('openai', 'gpt-4o',  DATE '2020-01-01', 100.000000, 100.000000, NULL,     NULL, 'seed'),
  ('openai', 'gpt-4o',  DATE '2024-01-01',   2.000000,   8.000000, 0.500000, NULL, 'seed'),
  ('openai', 'o4-mini', DATE '2024-01-01',   1.000000,   4.000000, NULL,     NULL, 'seed')
ON CONFLICT DO NOTHING;
