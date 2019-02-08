package com.google.swarm.tokenization;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.apache.beam.sdk.io.Compression;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.vendor.guava.v20.com.google.common.base.Charsets;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.services.bigquery.model.TableRow;
import com.google.privacy.dlp.v2.Table;

/** Test cases for the {@link FileIOToBigQueryStreaming} class. */
@RunWith(JUnit4.class)
public class FileIOToBigQueryStreamingTest {

	public static final Logger LOG = LoggerFactory.getLogger(FileIOToBigQueryStreamingTest.class);

	@Rule
	public final transient TestPipeline p = TestPipeline.create();
	
	@ClassRule
	public static TemporaryFolder tempFolder = new TemporaryFolder();
	
	private static final String TOKENIZE_FILE_NAME = "tokenization_data.csv";
	private static StringBuilder TOKENIZE_FILE_CONTENTS = new StringBuilder();
	private static String HEADER = "CardTypeCode,CardTypeFullName,IssuingBank,CardNumber,CardHoldersName,CVVCVV2,IssueDate,ExpiryDate,"
			+ "BillingDate,CardPIN,CreditLimit";
	private static String CONTENTS = "MC,Master Card,Wells Fargo,E5ssxfuqnGfF36Kk,Jeremy O Wilson,"
			+ "NK3,12/2007,12/2008,3,vmFF,19800";
	private static String tokenizedFilePath;
	
	@BeforeClass
	public static void setupClass() throws IOException {

		tokenizedFilePath = tempFolder.newFile(TOKENIZE_FILE_NAME).getAbsolutePath();
		TOKENIZE_FILE_CONTENTS.append(HEADER+"\n");
		TOKENIZE_FILE_CONTENTS.append(CONTENTS);
		Files.write(new File(tokenizedFilePath).toPath(), TOKENIZE_FILE_CONTENTS.toString().getBytes(Charsets.UTF_8));

	}

	@After
	public void cleanupTestEnvironment() {
		tempFolder.delete();
	}

	/**
	 * Tests reading from a sample CSV file in chunks and create DLP Table from the
	 * contents and process the contents by converting to Table Row
	 */

	@Test
	public void testFileIOToBigQueryStreamingE2E() throws IOException {
		ValueProvider<Integer> batchSize = p.newProvider(10);

		PCollection<KV<String, Table>> dlpTable = p.apply("Match", FileIO.match().filepattern(tokenizedFilePath))
				.apply("Read File", FileIO.readMatches().withCompression(Compression.UNCOMPRESSED))
				.apply("Create DLP Table", ParDo.of(new FileIOToBigQueryStreaming.CSVReader(batchSize)));
				
		PAssert.that(dlpTable).satisfies(collection -> {

			KV<String, Table> tableData = collection.iterator().next();
			assertThat(tableData.getKey(), is(equalTo("tokenization_data")));
			assertThat(tableData.getValue().getHeadersCount(), is(equalTo(11)));
			assertThat(tableData.getValue().getRowsCount(), is(equalTo(1)));
			return null;
		});
		
		PCollection<KV<String, TableRow>> tableRowMap=dlpTable
				.apply(ParDo.of(new FileIOToBigQueryStreaming.TableRowProcessorDoFn()));
		
		PAssert.that(tableRowMap)
        .satisfies(
            collection -> {
              KV<String, TableRow> result = collection.iterator().next();
              
              assertThat(result.getValue().get("CardTypeCode"), is(equalTo("MC")));
              assertThat(result.getValue().get("CardTypeFullName"), is(equalTo("Master Card")));
              assertThat(result.getValue().get("IssuingBank"), is(equalTo("Wells Fargo")));
              assertThat(result.getValue().get("CardNumber"), is(equalTo("E5ssxfuqnGfF36Kk")));
              assertThat(result.getValue().get("CardHoldersName"), is(equalTo("Jeremy O Wilson")));
              assertThat(result.getValue().get("CVVCVV2"), is(equalTo("NK3")));
              assertThat(result.getValue().get("IssueDate"), is(equalTo("12/2007")));
              assertThat(result.getValue().get("ExpiryDate"), is(equalTo("12/2008")));
              assertThat(result.getValue().get("BillingDate"), is(equalTo("3")));
              assertThat(result.getValue().get("CardPIN"), is(equalTo("vmFF")));
              assertThat(result.getValue().get("CreditLimit"), is(equalTo("19800")));
              return null;
            });
		p.run();
	}
	

	
	

}
