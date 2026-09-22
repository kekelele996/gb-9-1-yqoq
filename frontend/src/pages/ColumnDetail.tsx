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
  message,
  Spin,
} from 'antd'
import { columnApi } from '../api/column'
import { orderApi } from '../api/order'
import type { Column, Article, Order, Subscription } from '../types'
import dayjs from 'dayjs'

const { Title, Text, Paragraph } = Typography

type PlanType = 'MONTHLY' | 'QUARTERLY' | 'YEARLY'

const planLabels: Record<PlanType, string> = {
  MONTHLY: '月付',
  QUARTERLY: '季付',
  YEARLY: '年付',
}

function ColumnDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [column, setColumn] = useState<Column | null>(null)
  const [articles, setArticles] = useState<Article[]>([])
  const [loading, setLoading] = useState(false)
  const [subscribeModalVisible, setSubscribeModalVisible] = useState(false)
  const [selectedPlan, setSelectedPlan] = useState<PlanType>('MONTHLY')
  const [stage, setStage] = useState<'select' | 'ordered' | 'paid'>('select')
  const [order, setOrder] = useState<Order | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [paying, setPaying] = useState(false)
  const [mySubscription, setMySubscription] = useState<Subscription | null>(null)

  useEffect(() => {
    if (id) {
      loadColumnDetail()
      loadMySubscription()
    }
  }, [id])

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
    if (!id || !localStorage.getItem('token')) return
    try {
      const res = await columnApi.mySubscriptions()
      const subs: Subscription[] = res.data?.data?.content || res.data?.data || []
      const active = subs.find(
        (sub) =>
          sub.columnId === id &&
          sub.status === 'ACTIVE' &&
          dayjs(sub.endDate).isAfter(dayjs())
      )
      setMySubscription(active || null)
    } catch (error) {
      console.error('Failed to load my subscription:', error)
    }
  }

  const openSubscribeModal = () => {
    if (!localStorage.getItem('token')) {
      message.warning('请先登录后再订阅')
      navigate('/login')
      return
    }
    setStage('select')
    setOrder(null)
    setSubscribeModalVisible(true)
  }

  const handleCreateOrder = async () => {
    if (!id) return
    setSubmitting(true)
    try {
      const res = await columnApi.subscribe(id, selectedPlan)
      const body = res.data
      if (!body?.success) {
        message.error(body?.message || '下单失败')
        return
      }
      setOrder(body.data)
      setStage('ordered')
      message.success('下单成功，请完成支付')
    } catch (error) {
      console.error('Create order failed:', error)
    } finally {
      setSubmitting(false)
    }
  }

  const handlePay = async () => {
    if (!order) return
    setPaying(true)
    try {
      const res = await orderApi.pay(order.id)
      const body = res.data
      if (!body?.success) {
        message.error(body?.message || '支付失败')
        return
      }
      setOrder(body.data)
      setStage('paid')
      message.success('支付成功，订阅已开通')
      loadColumnDetail()
      await loadMySubscription()
    } catch (error) {
      console.error('Pay failed:', error)
    } finally {
      setPaying(false)
    }
  }

  const planOptions = column
    ? [
        { label: `月付 ¥${column.monthlyPrice}`, value: 'MONTHLY' as PlanType },
        { label: `季付 ¥${column.quarterlyPrice}`, value: 'QUARTERLY' as PlanType },
        { label: `年付 ¥${column.yearlyPrice}`, value: 'YEARLY' as PlanType },
      ]
    : []

  const modalFooter = () => {
    if (stage === 'select') {
      return (
        <>
          <Button onClick={() => setSubscribeModalVisible(false)}>取消</Button>
          <Button type="primary" loading={submitting} onClick={handleCreateOrder}>
            提交订单
          </Button>
        </>
      )
    }
    if (stage === 'ordered') {
      return (
        <>
          <Button onClick={() => setSubscribeModalVisible(false)}>稍后支付</Button>
          <Button type="primary" loading={paying} onClick={handlePay}>
            立即支付
          </Button>
        </>
      )
    }
    return (
      <Button
        type="primary"
        onClick={() => {
          setSubscribeModalVisible(false)
          navigate('/my/subscriptions')
        }}
      >
        查看我的订阅
      </Button>
    )
  }

  if (loading || !column) {
    return <Spin style={{ display: 'flex', justifyContent: 'center', marginTop: 100 }} />
  }

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
              {mySubscription && (
                <Tag color="green">
                  已订阅至 {dayjs(mySubscription.endDate).format('YYYY-MM-DD')}
                </Tag>
              )}
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
              <Button type="primary" size="large" onClick={openSubscribeModal}>
                {mySubscription ? '续费订阅' : '立即订阅'}
              </Button>
            </div>
          </div>
        </div>
      </Card>

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
                description={article.summary || article.description}
              />
            </List.Item>
          )}
        />
      </Card>

      <Modal
        title={stage === 'select' ? '选择订阅计划' : stage === 'ordered' ? '订单待支付' : '支付结果'}
        open={subscribeModalVisible}
        onCancel={() => setSubscribeModalVisible(false)}
        footer={modalFooter()}
        closable={!paying}
        maskClosable={!paying}
      >
        {stage === 'select' && (
          <Radio.Group
            value={selectedPlan}
            onChange={(e) => setSelectedPlan(e.target.value as PlanType)}
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
        )}

        {stage === 'ordered' && order && (
          <div>
            <Alert
              type="info"
              message="订单已生成，请完成支付"
              style={{ marginBottom: 16 }}
              showIcon
            />
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="订单号">{order.orderNo}</Descriptions.Item>
              <Descriptions.Item label="商品">{order.itemTitle}</Descriptions.Item>
              <Descriptions.Item label="金额">
                <Text strong style={{ color: '#f5222d' }}>
                  ¥{order.amount}
                </Text>
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color="orange">待支付</Tag>
              </Descriptions.Item>
            </Descriptions>
          </div>
        )}

        {stage === 'paid' && order && (
          <div>
            <Alert
              type="success"
              message="支付成功，订阅已开通"
              style={{ marginBottom: 16 }}
              showIcon
            />
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="订单号">{order.orderNo}</Descriptions.Item>
              <Descriptions.Item label="商品">{order.itemTitle}</Descriptions.Item>
              <Descriptions.Item label="实付金额">
                <Text strong style={{ color: '#f5222d' }}>
                  ¥{order.amount}
                </Text>
              </Descriptions.Item>
              <Descriptions.Item label="支付方式">支付宝</Descriptions.Item>
              {mySubscription && (
                <Descriptions.Item label="订阅有效期至">
                  {dayjs(mySubscription.endDate).format('YYYY-MM-DD HH:mm')}
                </Descriptions.Item>
              )}
            </Descriptions>
          </div>
        )}
      </Modal>
    </div>
  )
}

export default ColumnDetail
