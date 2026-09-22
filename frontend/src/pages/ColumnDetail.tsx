import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  Card,
  Typography,
  Button,
  List,
  Tag,
  Avatar,
  Descriptions,
  Radio,
  Modal,
  Alert,
  Result,
  Spin,
  Space,
  message,
} from 'antd'
import { useSelector } from 'react-redux'
import { RootState } from '../store'
import { columnApi } from '../api/column'
import { orderApi } from '../api/order'
import type { Column, Article, Order, Subscription } from '../types'
import dayjs from 'dayjs'

const { Title, Text, Paragraph } = Typography

const PLAN_LABELS: Record<string, string> = {
  MONTHLY: '月付',
  QUARTERLY: '季付',
  YEARLY: '年付',
}

function ColumnDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { user } = useSelector((state: RootState) => state.auth)
  const [column, setColumn] = useState<Column | null>(null)
  const [articles, setArticles] = useState<Article[]>([])
  const [loading, setLoading] = useState(false)
  const [subscribeModalVisible, setSubscribeModalVisible] = useState(false)
  const [selectedPlan, setSelectedPlan] = useState<'MONTHLY' | 'QUARTERLY' | 'YEARLY'>('MONTHLY')
  const [submitting, setSubmitting] = useState(false)
  const [payingOrderNo, setPayingOrderNo] = useState<string | null>(null)
  const [subscription, setSubscription] = useState<Subscription | null>(null)
  const [pendingOrder, setPendingOrder] = useState<Order | null>(null)

  useEffect(() => {
    if (id) {
      loadColumnDetail()
    }
  }, [id])

  useEffect(() => {
    if (id && user) {
      loadMySubscription()
    }
  }, [id, user])

  const loadColumnDetail = async () => {
    if (!id) return
    setLoading(true)
    try {
      const [columnRes, articlesRes] = await Promise.all([
        columnApi.getById(id),
        columnApi.getArticles(id),
      ])
      setColumn(columnRes.data?.data || columnRes.data)
      setArticles(articlesRes.data?.data?.content || articlesRes.data || [])
    } catch (error) {
      console.error('Failed to load column:', error)
    } finally {
      setLoading(false)
    }
  }

  const loadMySubscription = async () => {
    if (!id) return
    try {
      const res = await columnApi.mySubscription(id)
      const data = res.data?.data ?? null
      setSubscription(data)
    } catch (error) {
      console.error('Failed to load subscription:', error)
    }
  }

  // 选择套餐后生成待支付订单
  const handleCreateOrder = async () => {
    if (!id) return
    if (!user) {
      Modal.confirm({
        title: '请先登录',
        content: '登录后即可下单订阅该专栏',
        okText: '去登录',
        cancelText: '取消',
        onOk: () => navigate('/login'),
      })
      setSubscribeModalVisible(false)
      return
    }
    setSubmitting(true)
    try {
      const res = await orderApi.create({
        type: 'COLUMN_SUBSCRIPTION',
        itemId: id,
        plan: selectedPlan,
      })
      if (res.data?.success === false) {
        message.error(res.data?.message || '下单失败')
        return
      }
      const order: Order = res.data?.data
      setSubscribeModalVisible(false)
      setPendingOrder(order)
    } catch (error) {
      console.error('Create order failed:', error)
    } finally {
      setSubmitting(false)
    }
  }

  // 模拟支付宝支付：支付成功后订单转为已支付并开通/续接订阅
  const handlePay = async (order: Order) => {
    setPayingOrderNo(order.orderNo)
    try {
      const res = await orderApi.pay(order.id)
      if (res.data?.success === false) {
        message.error(res.data?.message || '支付失败')
        return
      }
      setPendingOrder(null)
      await loadMySubscription()
      Modal.success({
        title: '支付成功',
        content: (
          <div>
            <p>订单号：{order.orderNo}</p>
            <p>支付金额：¥{order.amount}</p>
            <p>订阅已开通，可在「我的订阅」中查看有效期。</p>
          </div>
        ),
        okText: '知道了',
      })
    } catch (error) {
      console.error('Pay failed:', error)
    } finally {
      setPayingOrderNo(null)
    }
  }

  const planOptions = column
    ? [
        { label: `月付 ¥${column.monthlyPrice}`, value: 'MONTHLY' },
        { label: `季付 ¥${column.quarterlyPrice}`, value: 'QUARTERLY' },
        { label: `年付 ¥${column.yearlyPrice}`, value: 'YEARLY' },
      ]
    : []

  if (loading || !column) {
    return <Spin style={{ display: 'flex', justifyContent: 'center', marginTop: 100 }} />
  }

  const isSubscribed = !!subscription?.active

  return (
    <div>
      <Card>
        <div style={{ display: 'flex', gap: 24 }}>
          <div
            style={{
              width: 240,
              height: 320,
              background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
              borderRadius: 8,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: '#fff',
              fontSize: 96,
              flexShrink: 0,
            }}
          >
            📚
          </div>
          <div style={{ flex: 1 }}>
            <Title level={2}>{column.title}</Title>
            <div style={{ marginBottom: 16 }}>
              <Avatar icon={<span>👤</span>} src={column.creator?.avatar} />
              <Text style={{ marginLeft: 8 }}>{column.creator?.username}</Text>
              <Tag color="purple" style={{ marginLeft: 8 }}>
                {column.category}
              </Tag>
            </div>
            <Paragraph type="secondary">{column.description}</Paragraph>
            <Descriptions column={3} style={{ marginTop: 16 }}>
              <Descriptions.Item label="文章数">{column.articleCount}</Descriptions.Item>
              <Descriptions.Item label="订阅数">{column.subscriberCount}</Descriptions.Item>
              <Descriptions.Item label="月付价格" className="price-text">
                ¥{column.monthlyPrice}
              </Descriptions.Item>
            </Descriptions>
            <div style={{ marginTop: 24 }}>
              <Button
                type="primary"
                size="large"
                onClick={() => setSubscribeModalVisible(true)}
              >
                {isSubscribed ? '续费专栏' : '立即订阅'}
              </Button>
            </div>
          </div>
        </div>
      </Card>

      {/* 下单 / 支付结果：与我的订单、我的订阅对应同一笔订单 */}
      {subscription && (
        <Card
          title={subscription.active ? '我的订阅（有效）' : '我的订阅（已过期）'}
          style={{ marginTop: 24 }}
        >
          <Alert
            type={subscription.active ? 'success' : 'warning'}
            showIcon
            style={{ marginBottom: 16 }}
            message={
              subscription.active
                ? `订阅有效，到期时间 ${dayjs(subscription.endDate).format('YYYY-MM-DD HH:mm')}`
                : '订阅已过期，续费后可继续阅读全部文章'
            }
          />
          <Descriptions column={{ xs: 1, sm: 2, md: 3 }}>
            <Descriptions.Item label="套餐">
              <Tag color="purple">{PLAN_LABELS[subscription.plan] || subscription.plan}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="订单号">{subscription.orderNo || '-'}</Descriptions.Item>
            <Descriptions.Item label="支付金额">
              {subscription.amount != null ? `¥${subscription.amount}` : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="开始时间">
              {dayjs(subscription.startDate).format('YYYY-MM-DD')}
            </Descriptions.Item>
            <Descriptions.Item label="到期时间">
              {dayjs(subscription.endDate).format('YYYY-MM-DD')}
            </Descriptions.Item>
            <Descriptions.Item label="状态">
              {subscription.active ? <Tag color="green">有效</Tag> : <Tag color="red">已过期</Tag>}
            </Descriptions.Item>
          </Descriptions>
        </Card>
      )}

      <Card title="文章列表" style={{ marginTop: 24 }}>
        <List
          dataSource={articles}
          renderItem={(article, index) => (
            <List.Item
              actions={[
                <Button
                  type="link"
                  key="read"
                  onClick={() => navigate(`/columns/${id}/articles/${article.id}`)}
                >
                  阅读
                </Button>,
              ]}
            >
              <List.Item.Meta
                title={
                  <span>
                    <Text type="secondary" style={{ marginRight: 12 }}>
                      #{index + 1}
                    </Text>
                    {article.title}
                  </span>
                }
                description={article.summary}
              />
            </List.Item>
          )}
        />
      </Card>

      {/* 第一步：选择套餐，生成待支付订单 */}
      <Modal
        title={isSubscribed ? '续费专栏（新方案将在当前到期日后续接）' : '选择订阅计划'}
        open={subscribeModalVisible}
        onOk={handleCreateOrder}
        onCancel={() => setSubscribeModalVisible(false)}
        confirmLoading={submitting}
        okText="提交订单"
        cancelText="取消"
      >
        {isSubscribed && subscription && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={`当前订阅到期时间：${dayjs(subscription.endDate).format('YYYY-MM-DD')}，续费时长将从该日期起算`}
          />
        )}
        <Radio.Group
          value={selectedPlan}
          onChange={(e) =>
            setSelectedPlan(e.target.value as 'MONTHLY' | 'QUARTERLY' | 'YEARLY')
          }
          style={{ width: '100%' }}
        >
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {planOptions.map((option) => (
              <Radio value={option.value} key={option.value}>
                {option.label}
              </Radio>
            ))}
          </div>
        </Radio.Group>
      </Modal>

      {/* 第二步：确认订单并支付（支付宝沙箱模拟） */}
      <Modal
        title="订单提交成功，请完成支付"
        open={!!pendingOrder}
        onCancel={() => setPendingOrder(null)}
        footer={null}
        maskClosable={false}
        width={480}
      >
        {pendingOrder && (
          <Result
            status="info"
            title="待支付"
            subTitle={
              <div>
                <div>订单号：{pendingOrder.orderNo}</div>
                <div>商品：{pendingOrder.itemTitle}</div>
                <div>套餐：{PLAN_LABELS[pendingOrder.plan || ''] || pendingOrder.plan}</div>
                <div style={{ fontSize: 20, fontWeight: 600, color: '#f5222d', marginTop: 8 }}>
                  应付金额：¥{pendingOrder.amount}
                </div>
              </div>
            }
            extra={[
              <Space key="actions">
                <Button onClick={() => setPendingOrder(null)}>稍后支付</Button>
                <Button
                  type="primary"
                  loading={payingOrderNo === pendingOrder.orderNo}
                  onClick={() => handlePay(pendingOrder)}
                >
                  支付宝支付
                </Button>
              </Space>,
            ]}
          />
        )}
      </Modal>
    </div>
  )
}

export default ColumnDetail
