-- Whole-book fallback uses the complete saved book name as its single topic name.
-- This only expands the existing capacity; it does not rename or truncate topic data.
ALTER TABLE topic_details MODIFY COLUMN topic_name VARCHAR(255) NOT NULL;
