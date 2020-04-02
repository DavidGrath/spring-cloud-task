/*
 * Copyright 2020-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.task.batch.autoconfigure;

import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.item.file.FlatFileFooterCallback;
import org.springframework.batch.item.file.FlatFileHeaderCallback;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.transform.FieldExtractor;
import org.springframework.batch.item.file.transform.LineAggregator;
import org.springframework.batch.item.file.transform.PassThroughLineAggregator;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.batch.test.AssertFile;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.FileCopyUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * @author Michael Minella
 */
public class FlatFileItemWriterAutoConfigurationTests {

	private File outputFile;

	@Before
	public void setUp() throws Exception {
		this.outputFile = File.createTempFile("flatfile-config-test-output", ".tmp");
	}

	@After
	public void tearDown() {
		this.outputFile.delete();
	}

	@Test
	public void testValidation() {

		FlatFileItemWriterProperties properties = new FlatFileItemWriterProperties();

		properties.setFormatted(true);
		properties.setDelimited(true);

		FlatFileItemWriterAutoConfiguration configuration = new FlatFileItemWriterAutoConfiguration(
				properties);

		try {
			configuration.itemWriter();
		}
		catch (IllegalStateException ise) {
			assertThat(ise.getMessage()).isEqualTo(
					"An output file must be either delimited or formatted or a custom "
							+ "LineAggregator must be provided. Your current configuration specifies both delimited and formatted");
		}
		catch (Exception e) {
			fail("Incorrect exception thrown", e);
		}

		properties.setFormatted(true);
		properties.setDelimited(false);

		ReflectionTestUtils.setField(configuration, "lineAggregator",
				new PassThroughLineAggregator<>());

		try {
			configuration.itemWriter();
		}
		catch (IllegalStateException ise) {
			assertThat(ise.getMessage())
					.isEqualTo("A LineAggregator must be configured if the "
							+ "output is not formatted or delimited");
		}
		catch (Exception e) {
			fail("Incorrect exception thrown", e);
		}

		properties.setFormatted(false);
		properties.setDelimited(true);

		try {
			configuration.itemWriter();
		}
		catch (IllegalStateException ise) {
			assertThat(ise.getMessage())
					.isEqualTo("A LineAggregator must be configured if the "
							+ "output is not formatted or delimited");
		}
		catch (Exception e) {
			fail("Incorrect exception thrown", e);
		}
	}

	@Test
	public void testDelimitedFileGeneration() {
		ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
				.withUserConfiguration(DelimitedJobConfiguration.class)
				.withConfiguration(
						AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
								BatchAutoConfiguration.class,
								SingleStepJobAutoConfiguration.class,
								FlatFileItemWriterAutoConfiguration.class))
				.withPropertyValues("spring.batch.job.jobName=job",
						"spring.batch.job.stepName=step1", "spring.batch.job.chunkSize=5",
						"spring.batch.job.flatfilewriter.name=fooWriter",
						String.format(
								"spring.batch.job.flatfilewriter.resource=file://%s",
								this.outputFile.getAbsolutePath()),
						"spring.batch.job.flatfilewriter.encoding=UTF-16",
						"spring.batch.job.flatfilewriter.saveState=false",
						"spring.batch.job.flatfilewriter.shouldDeleteIfEmpty=true",
						"spring.batch.job.flatfilewriter.delimited=true",
						"spring.batch.job.flatfilewriter.names=item",
						"spring.batch.job.flatfilewriter.append=true",
						"spring.batch.job.flatfilewriter.forceSync=true",
						"spring.batch.job.flatfilewriter.shouldDeleteIfExists=false",
						"spring.batch.job.flatfilewriter.transactional=false");

		applicationContextRunner.run((context) -> {
			JobLauncher jobLauncher = context.getBean(JobLauncher.class);

			Job job = context.getBean(Job.class);

			JobExecution jobExecution = jobLauncher.run(job, new JobParameters());

			JobExplorer jobExplorer = context.getBean(JobExplorer.class);

			while (jobExplorer.getJobExecution(jobExecution.getJobId()).isRunning()) {
				Thread.sleep(1000);
			}

			FlatFileItemWriter writer = context.getBean(FlatFileItemWriter.class);

			AssertFile.assertLineCount(3, this.outputFile);
			AssertFile.assertFileEquals(new ClassPathResource("writerTestUTF16.txt"),
					new FileSystemResource(this.outputFile));

			assertThat((Boolean) ReflectionTestUtils.getField(writer, "saveState"))
					.isFalse();
			assertThat((Boolean) ReflectionTestUtils.getField(writer, "append")).isTrue();
			assertThat((Boolean) ReflectionTestUtils.getField(writer, "forceSync"))
					.isTrue();
			assertThat((Boolean) ReflectionTestUtils.getField(writer,
					"shouldDeleteIfExists")).isFalse();
			assertThat((Boolean) ReflectionTestUtils.getField(writer, "transactional"))
					.isFalse();
		});
	}

	@Test
	public void testFormattedFileGeneration() {
		ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
				.withUserConfiguration(FormattedJobConfiguration.class)
				.withConfiguration(
						AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
								BatchAutoConfiguration.class,
								SingleStepJobAutoConfiguration.class,
								FlatFileItemWriterAutoConfiguration.class))
				.withPropertyValues("spring.batch.job.jobName=job",
						"spring.batch.job.stepName=step1", "spring.batch.job.chunkSize=2",
						"spring.batch.job.flatfilewriter.name=fooWriter",
						String.format(
								"spring.batch.job.flatfilewriter.resource=file://%s",
								this.outputFile.getAbsolutePath()),
						"spring.batch.job.flatfilewriter.encoding=UTF-8",
						"spring.batch.job.flatfilewriter.formatted=true",
						"spring.batch.job.flatfilewriter.names=item",
						"spring.batch.job.flatfilewriter.format=item = %s",
						"spring.batch.job.flatfilewriter.minimumLength=8",
						"spring.batch.job.flatfilewriter.maximumLength=10");

		applicationContextRunner.run((context) -> {
			JobLauncher jobLauncher = context.getBean(JobLauncher.class);

			Job job = context.getBean(Job.class);

			JobExecution jobExecution = jobLauncher.run(job, new JobParameters());

			JobExplorer jobExplorer = context.getBean(JobExplorer.class);

			while (jobExplorer.getJobExecution(jobExecution.getJobId()).isRunning()) {
				Thread.sleep(1000);
			}

			AssertFile.assertLineCount(2, this.outputFile);

			String results = FileCopyUtils.copyToString(new InputStreamReader(
					new FileSystemResource(this.outputFile).getInputStream()));
			assertThat(results).isEqualTo("item = foo\nitem = bar\n");
		});
	}

	@Test
	public void testFormattedFieldExtractorFileGeneration() {
		ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
				.withUserConfiguration(FormattedFieldExtractorJobConfiguration.class)
				.withConfiguration(
						AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
								BatchAutoConfiguration.class,
								SingleStepJobAutoConfiguration.class,
								FlatFileItemWriterAutoConfiguration.class))
				.withPropertyValues("spring.batch.job.jobName=job",
						"spring.batch.job.stepName=step1", "spring.batch.job.chunkSize=2",
						"spring.batch.job.flatfilewriter.name=fooWriter",
						String.format(
								"spring.batch.job.flatfilewriter.resource=file://%s",
								this.outputFile.getAbsolutePath()),
						"spring.batch.job.flatfilewriter.encoding=UTF-8",
						"spring.batch.job.flatfilewriter.formatted=true",
						"spring.batch.job.flatfilewriter.names=item",
						"spring.batch.job.flatfilewriter.format=item = %s");

		applicationContextRunner.run((context) -> {
			JobLauncher jobLauncher = context.getBean(JobLauncher.class);

			Job job = context.getBean(Job.class);

			JobExecution jobExecution = jobLauncher.run(job, new JobParameters());

			JobExplorer jobExplorer = context.getBean(JobExplorer.class);

			while (jobExplorer.getJobExecution(jobExecution.getJobId()).isRunning()) {
				Thread.sleep(1000);
			}

			AssertFile.assertLineCount(3, this.outputFile);

			String results = FileCopyUtils.copyToString(new InputStreamReader(
					new FileSystemResource(this.outputFile).getInputStream()));
			assertThat(results).isEqualTo("item = f\nitem = b\nitem = b\n");
		});
	}

	@Test
	public void testFieldExtractorFileGeneration() {
		ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
				.withUserConfiguration(FieldExtractorConfiguration.class)
				.withConfiguration(
						AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
								BatchAutoConfiguration.class,
								SingleStepJobAutoConfiguration.class,
								FlatFileItemWriterAutoConfiguration.class))
				.withPropertyValues("spring.batch.job.jobName=job",
						"spring.batch.job.stepName=step1", "spring.batch.job.chunkSize=5",
						"spring.batch.job.flatfilewriter.name=fooWriter",
						String.format(
								"spring.batch.job.flatfilewriter.resource=file://%s",
								this.outputFile.getAbsolutePath()),
						"spring.batch.job.flatfilewriter.encoding=UTF-8",
						"spring.batch.job.flatfilewriter.delimited=true");

		applicationContextRunner.run((context) -> {
			JobLauncher jobLauncher = context.getBean(JobLauncher.class);

			Job job = context.getBean(Job.class);

			JobExecution jobExecution = jobLauncher.run(job, new JobParameters());

			JobExplorer jobExplorer = context.getBean(JobExplorer.class);

			while (jobExplorer.getJobExecution(jobExecution.getJobId()).isRunning()) {
				Thread.sleep(1000);
			}

			AssertFile.assertLineCount(3, this.outputFile);

			String results = FileCopyUtils.copyToString(new InputStreamReader(
					new FileSystemResource(this.outputFile).getInputStream()));
			assertThat(results).isEqualTo("f\nb\nb\n");
		});
	}

	@Test
	public void testCustomLineAggregatorFileGeneration() {
		ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
				.withUserConfiguration(LineAggregatorConfiguration.class)
				.withConfiguration(
						AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
								BatchAutoConfiguration.class,
								SingleStepJobAutoConfiguration.class,
								FlatFileItemWriterAutoConfiguration.class))
				.withPropertyValues("spring.batch.job.jobName=job",
						"spring.batch.job.stepName=step1", "spring.batch.job.chunkSize=5",
						"spring.batch.job.flatfilewriter.name=fooWriter",
						String.format(
								"spring.batch.job.flatfilewriter.resource=file://%s",
								this.outputFile.getAbsolutePath()),
						"spring.batch.job.flatfilewriter.encoding=UTF-8");

		applicationContextRunner.run((context) -> {
			JobLauncher jobLauncher = context.getBean(JobLauncher.class);

			Job job = context.getBean(Job.class);

			JobExecution jobExecution = jobLauncher.run(job, new JobParameters());

			JobExplorer jobExplorer = context.getBean(JobExplorer.class);

			while (jobExplorer.getJobExecution(jobExecution.getJobId()).isRunning()) {
				Thread.sleep(1000);
			}

			AssertFile.assertLineCount(3, this.outputFile);

			String results = FileCopyUtils.copyToString(new InputStreamReader(
					new FileSystemResource(this.outputFile).getInputStream()));
			assertThat(results).isEqualTo("{item=foo}\n{item=bar}\n{item=baz}\n");
		});
	}

	@Test
	public void testHeaderFooterFileGeneration() {
		ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
				.withUserConfiguration(HeaderFooterConfiguration.class)
				.withConfiguration(
						AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
								BatchAutoConfiguration.class,
								SingleStepJobAutoConfiguration.class,
								FlatFileItemWriterAutoConfiguration.class))
				.withPropertyValues("spring.batch.job.jobName=job",
						"spring.batch.job.stepName=step1", "spring.batch.job.chunkSize=5",
						"spring.batch.job.flatfilewriter.name=fooWriter",
						String.format(
								"spring.batch.job.flatfilewriter.resource=file://%s",
								this.outputFile.getAbsolutePath()),
						"spring.batch.job.flatfilewriter.encoding=UTF-8",
						"spring.batch.job.flatfilewriter.delimited=true",
						"spring.batch.job.flatfilewriter.names=item");

		applicationContextRunner.run((context) -> {
			JobLauncher jobLauncher = context.getBean(JobLauncher.class);

			Job job = context.getBean(Job.class);

			JobExecution jobExecution = jobLauncher.run(job, new JobParameters());

			JobExplorer jobExplorer = context.getBean(JobExplorer.class);

			while (jobExplorer.getJobExecution(jobExecution.getJobId()).isRunning()) {
				Thread.sleep(1000);
			}

			AssertFile.assertLineCount(5, this.outputFile);

			String results = FileCopyUtils.copyToString(new InputStreamReader(
					new FileSystemResource(this.outputFile).getInputStream()));
			assertThat(results).isEqualTo("header\nfoo\nbar\nbaz\nfooter");
		});
	}

	@Configuration
	@EnableBatchProcessing
	public static class DelimitedJobConfiguration {

		@Bean
		public ListItemReader<Map<Object, Object>> itemReader() {

			List<Map<Object, Object>> items = new ArrayList<>(3);

			items.add(Collections.singletonMap("item", "foo"));
			items.add(Collections.singletonMap("item", "bar"));
			items.add(Collections.singletonMap("item", "baz"));

			return new ListItemReader<>(items);
		}

	}

	@Configuration
	@EnableBatchProcessing
	public static class LineAggregatorConfiguration {

		@Bean
		public ListItemReader<Map<Object, Object>> itemReader() {

			List<Map<Object, Object>> items = new ArrayList<>(3);

			items.add(Collections.singletonMap("item", "foo"));
			items.add(Collections.singletonMap("item", "bar"));
			items.add(Collections.singletonMap("item", "baz"));

			return new ListItemReader<>(items);
		}

		@Bean
		public LineAggregator<Map<Object, Object>> lineAggregator() {
			return new PassThroughLineAggregator<>();
		}

	}

	@Configuration
	@EnableBatchProcessing
	public static class HeaderFooterConfiguration {

		@Bean
		public ListItemReader<Map<Object, Object>> itemReader() {

			List<Map<Object, Object>> items = new ArrayList<>(3);

			items.add(Collections.singletonMap("item", "foo"));
			items.add(Collections.singletonMap("item", "bar"));
			items.add(Collections.singletonMap("item", "baz"));

			return new ListItemReader<>(items);
		}

		@Bean
		public FlatFileHeaderCallback headerCallback() {
			return writer -> writer.append("header");
		}

		@Bean
		public FlatFileFooterCallback footerCallback() {
			return writer -> writer.append("footer");
		}

	}

	@Configuration
	@EnableBatchProcessing
	public static class FieldExtractorConfiguration {

		@Bean
		public ListItemReader<Map<Object, Object>> itemReader() {

			List<Map<Object, Object>> items = new ArrayList<>(3);

			items.add(Collections.singletonMap("item", "foo"));
			items.add(Collections.singletonMap("item", "bar"));
			items.add(Collections.singletonMap("item", "baz"));

			return new ListItemReader<>(items);
		}

		@Bean
		public FieldExtractor<Map<Object, Object>> lineAggregator() {
			return item -> {
				List<String> fields = new ArrayList<>(1);

				fields.add(((String) item.get("item")).substring(0, 1));
				return fields.toArray();
			};
		}

	}

	@Configuration
	@EnableBatchProcessing
	public static class FormattedJobConfiguration {

		@Bean
		public ListItemReader<Map<Object, Object>> itemReader() {

			List<Map<Object, Object>> items = new ArrayList<>(3);

			items.add(Collections.singletonMap("item", "foo"));
			items.add(Collections.singletonMap("item", "bar"));
			items.add(Collections.singletonMap("item", "tooLong"));

			return new ListItemReader<>(items);
		}

	}

	@Configuration
	@EnableBatchProcessing
	public static class FormattedFieldExtractorJobConfiguration {

		@Bean
		public FieldExtractor<Map<Object, Object>> lineAggregator() {
			return item -> {
				List<String> fields = new ArrayList<>(1);

				fields.add(((String) item.get("item")).substring(0, 1));
				return fields.toArray();
			};
		}

		@Bean
		public ListItemReader<Map<Object, Object>> itemReader() {

			List<Map<Object, Object>> items = new ArrayList<>(3);

			items.add(Collections.singletonMap("item", "foo"));
			items.add(Collections.singletonMap("item", "bar"));
			items.add(Collections.singletonMap("item", "baz"));

			return new ListItemReader<>(items);
		}

	}

}
