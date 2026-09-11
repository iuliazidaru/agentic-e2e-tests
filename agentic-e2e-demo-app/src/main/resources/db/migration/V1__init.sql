CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS products (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    price DECIMAL(10,2) NOT NULL,
    stock INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS orders (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id),
    product_id BIGINT REFERENCES products(id),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    shipping_name VARCHAR(100),
    shipping_address VARCHAR(255),
    created_at TIMESTAMP DEFAULT NOW()
);

-- Seed admin user (password: admin123 encoded with BCrypt)
INSERT INTO users (username, password, email, role, active)
VALUES ('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'admin@myapp.local', 'ADMIN', true)
ON CONFLICT (username) DO NOTHING;

-- Seed regular test user (password: test123)
INSERT INTO users (username, password, email, role, active)
VALUES ('testuser', '$2a$10$GRLdNijSQMUvl/au9ofL.eDwmoohzzS7.rmNSJZ.0FxO1GIUwyUiS', 'testuser@myapp.local', 'USER', true)
ON CONFLICT (username) DO NOTHING;

-- Seed product with id=123 (use sequence or explicit id)
INSERT INTO products (id, name, description, price, stock)
VALUES (123, 'Test Widget', 'A sample product for E2E testing', 29.99, 100)
ON CONFLICT DO NOTHING;

-- Reset sequence if needed
SELECT setval('products_id_seq', GREATEST(123, (SELECT MAX(id) FROM products)));
