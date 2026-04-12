package pegasus

import chisel3._
import chisel3.util.HasBlackBoxInline

// PegasusShell: inline Verilog BlackBox so firtool cannot optimise away
// the xdma_0 instance. Same pattern as PMCTraceDPI.
// Port names use io_ prefix to match PegasusHarness connections.
class PegasusShell extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val pcie_sys_clk    = Input(Clock())
    val pcie_sys_clk_gt = Input(Clock())
    val pcie_sys_rst_n  = Input(Bool())
    val pcie_exp_txp    = Output(UInt(16.W))
    val pcie_exp_txn    = Output(UInt(16.W))
    val pcie_exp_rxp    = Input(UInt(16.W))
    val pcie_exp_rxn    = Input(UInt(16.W))
    val hbm_ref_clk     = Input(Clock())

    val uart_tx = Input(Bool())

    val chip_mem_awid    = Input(UInt(6.W))
    val chip_mem_awaddr  = Input(UInt(33.W))
    val chip_mem_awlen   = Input(UInt(8.W))
    val chip_mem_awsize  = Input(UInt(3.W))
    val chip_mem_awburst = Input(UInt(2.W))
    val chip_mem_awvalid = Input(Bool())
    val chip_mem_awready = Output(Bool())

    val chip_mem_wdata  = Input(UInt(256.W))
    val chip_mem_wstrb  = Input(UInt(32.W))
    val chip_mem_wlast  = Input(Bool())
    val chip_mem_wvalid = Input(Bool())
    val chip_mem_wready = Output(Bool())

    val chip_mem_bid    = Output(UInt(6.W))
    val chip_mem_bresp  = Output(UInt(2.W))
    val chip_mem_bvalid = Output(Bool())
    val chip_mem_bready = Input(Bool())

    val chip_mem_arid    = Input(UInt(6.W))
    val chip_mem_araddr  = Input(UInt(33.W))
    val chip_mem_arlen   = Input(UInt(8.W))
    val chip_mem_arsize  = Input(UInt(3.W))
    val chip_mem_arburst = Input(UInt(2.W))
    val chip_mem_arvalid = Input(Bool())
    val chip_mem_arready = Output(Bool())

    val chip_mem_rid    = Output(UInt(6.W))
    val chip_mem_rdata  = Output(UInt(256.W))
    val chip_mem_rresp  = Output(UInt(2.W))
    val chip_mem_rlast  = Output(Bool())
    val chip_mem_rvalid = Output(Bool())
    val chip_mem_rready = Input(Bool())

    val dut_clk   = Output(Clock())
    val dut_reset = Output(Bool())
  })

  setInline("PegasusShell.v",
    """module PegasusShell(
      |  input         io_pcie_sys_clk,
      |  input         io_pcie_sys_clk_gt,
      |  input         io_pcie_sys_rst_n,
      |  output [15:0] io_pcie_exp_txp,
      |  output [15:0] io_pcie_exp_txn,
      |  input  [15:0] io_pcie_exp_rxp,
      |  input  [15:0] io_pcie_exp_rxn,
      |  input         io_hbm_ref_clk,
      |  input         io_uart_tx,
      |  input  [5:0]  io_chip_mem_awid,
      |  input  [32:0] io_chip_mem_awaddr,
      |  input  [7:0]  io_chip_mem_awlen,
      |  input  [2:0]  io_chip_mem_awsize,
      |  input  [1:0]  io_chip_mem_awburst,
      |  input         io_chip_mem_awvalid,
      |  output        io_chip_mem_awready,
      |  input  [255:0] io_chip_mem_wdata,
      |  input  [31:0] io_chip_mem_wstrb,
      |  input         io_chip_mem_wlast,
      |  input         io_chip_mem_wvalid,
      |  output        io_chip_mem_wready,
      |  output [5:0]  io_chip_mem_bid,
      |  output [1:0]  io_chip_mem_bresp,
      |  output        io_chip_mem_bvalid,
      |  input         io_chip_mem_bready,
      |  input  [5:0]  io_chip_mem_arid,
      |  input  [32:0] io_chip_mem_araddr,
      |  input  [7:0]  io_chip_mem_arlen,
      |  input  [2:0]  io_chip_mem_arsize,
      |  input  [1:0]  io_chip_mem_arburst,
      |  input         io_chip_mem_arvalid,
      |  output        io_chip_mem_arready,
      |  output [5:0]  io_chip_mem_rid,
      |  output [255:0] io_chip_mem_rdata,
      |  output [1:0]  io_chip_mem_rresp,
      |  output        io_chip_mem_rlast,
      |  output        io_chip_mem_rvalid,
      |  input         io_chip_mem_rready,
      |  output        io_dut_clk,
      |  output        io_dut_reset
      |);
      |
      |  wire _xdma_axi_aresetn;
      |
      |  xdma_0 xdma (
      |    .sys_clk           (io_pcie_sys_clk),
      |    .sys_clk_gt        (io_pcie_sys_clk_gt),
      |    .sys_rst_n         (io_pcie_sys_rst_n),
      |    .pci_exp_txp       (io_pcie_exp_txp),
      |    .pci_exp_txn       (io_pcie_exp_txn),
      |    .pci_exp_rxp       (io_pcie_exp_rxp),
      |    .pci_exp_rxn       (io_pcie_exp_rxn),
      |    .axi_aclk          (io_dut_clk),
      |    .axi_aresetn       (_xdma_axi_aresetn),
      |    .usr_irq_req       (1'b0),
      |    .m_axib_awready    (1'b0),
      |    .m_axib_wready     (1'b0),
      |    .m_axib_bid        (4'h0),
      |    .m_axib_bresp      (2'h0),
      |    .m_axib_bvalid     (1'b0),
      |    .m_axib_arready    (1'b0),
      |    .m_axib_rid        (4'h0),
      |    .m_axib_rdata      (512'h0),
      |    .m_axib_rresp      (2'h0),
      |    .m_axib_rlast      (1'b0),
      |    .m_axib_rvalid     (1'b0),
      |    .s_axil_awaddr     (32'h0),
      |    .s_axil_awprot     (3'h0),
      |    .s_axil_awvalid    (1'b0),
      |    .s_axil_wdata      (32'h0),
      |    .s_axil_wstrb      (4'h0),
      |    .s_axil_wvalid     (1'b0),
      |    .s_axil_bready     (1'b0),
      |    .s_axil_araddr     (32'h0),
      |    .s_axil_arprot     (3'h0),
      |    .s_axil_arvalid    (1'b0),
      |    .s_axil_rready     (1'b0),
      |    .s_axib_awid       (4'h0),
      |    .s_axib_awaddr     (32'h0),
      |    .s_axib_awregion   (4'h0),
      |    .s_axib_awlen      (8'h0),
      |    .s_axib_awsize     (3'h0),
      |    .s_axib_awburst    (2'h0),
      |    .s_axib_awvalid    (1'b0),
      |    .s_axib_wdata      (512'h0),
      |    .s_axib_wstrb      (64'h0),
      |    .s_axib_wlast      (1'b0),
      |    .s_axib_wvalid     (1'b0),
      |    .s_axib_bready     (1'b0),
      |    .s_axib_arid       (4'h0),
      |    .s_axib_araddr     (32'h0),
      |    .s_axib_arregion   (4'h0),
      |    .s_axib_arlen      (8'h0),
      |    .s_axib_arsize     (3'h0),
      |    .s_axib_arburst    (2'h0),
      |    .s_axib_arvalid    (1'b0),
      |    .s_axib_rready     (1'b0)
      |  );
      |
      |  assign io_dut_reset      = ~_xdma_axi_aresetn;
      |  assign io_chip_mem_awready = 1'b0;
      |  assign io_chip_mem_wready  = 1'b0;
      |  assign io_chip_mem_bid     = 6'h0;
      |  assign io_chip_mem_bresp   = 2'h0;
      |  assign io_chip_mem_bvalid  = 1'b0;
      |  assign io_chip_mem_arready = 1'b0;
      |  assign io_chip_mem_rid     = 6'h0;
      |  assign io_chip_mem_rdata   = 256'h0;
      |  assign io_chip_mem_rresp   = 2'h0;
      |  assign io_chip_mem_rlast   = 1'b0;
      |  assign io_chip_mem_rvalid  = 1'b0;
      |
      |endmodule
      |""".stripMargin)
}
