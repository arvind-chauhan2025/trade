package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DemoApplicationTests {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void contextLoads() {
	}

	/** Verifies PremiumReferenceService actually durably persists the day/rolling references (not just
	 * keeping them in memory), so a machine/app restart mid-day doesn't flush them. */
	@Test
	void premiumReferenceIsPersistedForToday() throws InterruptedException {
		Thread.sleep(3000);
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM premium_reference WHERE tick_date = ?",
				Integer.class, LocalDate.now());
		assertTrue(count != null && count >= 1,
				"Expected at least one premium_reference row persisted for today, found " + count);
	}

}
